package io.github.stream29.kodex.openai.modelcatalog

import io.github.stream29.kodex.openai.DefaultEffectiveContextWindowPercent
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.getOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * In-memory model metadata catalog backed by Codex `/models` responses.
 *
 * The initial [models] value is [BuiltInModelCatalog]. A catalog-owned
 * background scope refreshes on authentication changes when an auth store is
 * supplied, or once at startup for a fixed client.
 *
 * @property authStore Nullable for the fixed-client factory; `null` means there
 * is no authentication subscription and only the startup refresh runs automatically.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class OpenAiModelCatalogStoreImpl(
    private val client: OpenAiClient,
    private val authStore: OpenAiAuthStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : OpenAiModelCatalogStore {

    /**
     * Latest catalog snapshot in source model order, retaining the first
     * preset advertised for each reasoning effort.
     */
    override val models: StateFlow<List<ModelInfo>>
        field = MutableStateFlow(BuiltInModelCatalog)

    init {
        scope.launch {
            var accountKey: String? = null
            (authStore?.state ?: flowOf(null))
                .mapLatest { auth ->
                    val nextKey = auth.accountKey()
                    if (nextKey != accountKey) {
                        models.value = BuiltInModelCatalog
                        accountKey = nextKey
                    }
                    val refreshed = if (authStore == null || auth is OpenAiAuthState.Authenticated) {
                        try {
                            fetchModels()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            // Retain only this account's snapshot (or the bundled fallback).
                            models.value
                        }
                    } else {
                        BuiltInModelCatalog
                    }
                    auth to refreshed
                }
                .buffer(0)
                .collect { (auth, refreshed) ->
                    if (authStore == null || authStore.state.value == auth) {
                        models.value = refreshed
                    }
                }
        }
    }

    /** Fetches `/models` and returns the atomically published catalog snapshot. */
    override suspend fun refresh(): List<ModelInfo> {
        val auth = authStore?.state?.value
        val refreshed = fetchModels()
        currentCoroutineContext().ensureActive()
        if (authStore != null && authStore.state.value != auth) {
            throw CancellationException("Authentication changed during model catalog refresh.")
        }
        models.value = refreshed
        return refreshed
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

    private suspend fun fetchModels(): List<ModelInfo> =
        client.listModels().getOrThrow().models.normalizeReasoningPresets()
}

/**
 * Creates a live catalog. Supply the client's [authStore] to refresh on authentication
 * changes and discard remote metadata belonging to a previous account.
 * Omitting it retains the fixed-client, one-time startup refresh behavior.
 *
 * @param authStore Nullable for a fixed client; `null` disables authentication
 * observation, not authentication within the client itself.
 */
public fun OpenAiModelCatalog(
    client: OpenAiClient,
    authStore: OpenAiAuthStore? = null,
): OpenAiModelCatalogStore =
    OpenAiModelCatalogStoreImpl(client = client, authStore = authStore)

/** Backend-owned variant: background refresh is joined by the supplied owner. */
public fun CoroutineScope.ownedOpenAiModelCatalog(
    client: OpenAiClient,
    authStore: OpenAiAuthStore,
): OpenAiModelCatalogStore =
    OpenAiModelCatalogStoreImpl(
        client = client,
        authStore = authStore,
        scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[kotlinx.coroutines.Job])),
    )

/**
 * @receiver Nullable for a fixed client without an authentication subscription.
 * @return An account identity for invalidation, or `null` when no account is available.
 */
private fun OpenAiAuthState?.accountKey(): String? =
    (this as? OpenAiAuthState.Authenticated)?.credentials?.let {
        it.accountId?.takeIf(String::isNotBlank)
            ?: it.email?.takeIf(String::isNotBlank)
            ?: it.accessToken
    }

private fun List<ModelInfo>.normalizeReasoningPresets(): List<ModelInfo> =
    map { model ->
        val normalizedPresets = model.supportedReasoningLevels.distinctBy { preset -> preset.effort }
        if (normalizedPresets.size == model.supportedReasoningLevels.size) {
            model
        } else {
            model.copy(supportedReasoningLevels = normalizedPresets)
        }
    }

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
