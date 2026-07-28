package io.github.stream29.codex.lite.openai.modelcatalog

import io.github.stream29.codex.lite.openai.DefaultEffectiveContextWindowPercent
import io.github.stream29.codex.lite.openai.ModelInfo
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.codexclistorage.CodexModelsCache
import io.github.stream29.codex.lite.openai.getOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * In-memory model metadata catalog backed by Codex `/models` responses.
 *
 * The initial [models] value is [BuiltInModelCatalog]. A catalog-owned
 * background scope then attempts [refreshFromCodexCliCache] followed by
 * [refresh]. Failed sources leave the most recently published snapshot intact.
 *
 * @param codexCliStorage Installed Codex CLI storage used as a read-only
 * startup cache source.
 */
internal class OpenAiModelCatalogImpl(
    private val client: OpenAiClient,
    private val codexCliStorage: CodexCliStorage,
) : OpenAiModelCatalog {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Latest catalog snapshot, ordered exactly as supplied by its source. */
    override val models: StateFlow<List<ModelInfo>>
        field = MutableStateFlow(BuiltInModelCatalog)

    init {
        scope.launch {
            tryBootstrap { refreshFromCodexCliCache() }
            tryBootstrap { refreshAtStartup() }
        }
    }

    /** Fetches `/models` and returns the atomically published catalog snapshot. */
    override suspend fun refresh(): List<ModelInfo> {
        val refreshed = client.listModels().getOrThrow().models
        models.value = refreshed
        return refreshed
    }

    /**
     * Reads and publishes the installed Codex CLI cache without mutating it.
     *
     * @return Nullable because Codex CLI may not have created
     * `models_cache.json`; `null` means no cache file exists. An existing
     * cache is returned and published even when its model list is empty.
     */
    internal suspend fun refreshFromCodexCliCache(): CodexModelsCache? {
        val cache = codexCliStorage.readModelsCacheOrNull() ?: return null
        models.value = cache.models
        return cache
    }

    /** Cancels catalog-owned background work without closing the injected client. */
    override fun close(): Unit = scope.cancel()

    /**
     * Resolves [model] against the latest catalog snapshot.
     *
     * This follows Codex's longest-prefix lookup and its single-provider
     * namespace fallback. The requested slug is retained on the result so a
     * catalog entry such as `gpt-5.6` can describe `gpt-5.6-experimental`.
     * Unknown slugs receive Codex's conservative fallback metadata.
     */
    override fun resolve(model: OpenAiModelId): ModelInfo {
        val requestedSlug = model.value
        val matchingModel = models.value.longestPrefixMatch(requestedSlug)
            ?: requestedSlug.singleNamespaceSuffix()?.let(models.value::longestPrefixMatch)
        return matchingModel?.copy(slug = model) ?: fallbackModelInfo(model)
    }

    private suspend fun refreshAtStartup(): Unit =
        when (val result = client.listModels()) {
            is OpenAiResult.Success -> models.value = result.value.models
            is OpenAiResult.Failure -> Unit
        }

    private suspend fun tryBootstrap(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // The built-in or preceding snapshot remains usable after startup failures.
        }
    }
}

/** Creates a live model catalog backed by Codex CLI cache and OpenAI `/models`. */
public fun OpenAiModelCatalog(
    client: OpenAiClient,
    codexCliStorage: CodexCliStorage,
): OpenAiModelCatalog =
    OpenAiModelCatalogImpl(
        client = client,
        codexCliStorage = codexCliStorage,
    )

private fun List<ModelInfo>.longestPrefixMatch(requestedSlug: String): ModelInfo? =
    asSequence()
        .filter { candidate -> requestedSlug.startsWith(candidate.slug.value) }
        .maxByOrNull { candidate -> candidate.slug.value.length }

private fun String.singleNamespaceSuffix(): String? {
    val slashIndex = indexOf('/')
    if (slashIndex <= 0 || slashIndex == lastIndex || indexOf('/', slashIndex + 1) >= 0) {
        return null
    }
    val namespace = substring(0, slashIndex)
    if (namespace.any { character -> !character.isAsciiProviderNamespaceCharacter() }) {
        return null
    }
    return substring(slashIndex + 1)
}

private fun Char.isAsciiProviderNamespaceCharacter(): Boolean =
    isAsciiLetterOrDigit() || this == '_' || this == '-'

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun fallbackModelInfo(model: OpenAiModelId): ModelInfo =
    ModelInfo(
        slug = model,
        displayName = model.value,
        contextWindow = FallbackContextWindow,
        maxContextWindow = FallbackContextWindow,
        effectiveContextWindowPercent = DefaultEffectiveContextWindowPercent,
    )

private const val FallbackContextWindow: Long = 272_000L
