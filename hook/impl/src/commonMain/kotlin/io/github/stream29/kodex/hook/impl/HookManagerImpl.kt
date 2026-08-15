package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.hook.contract.HookCodexImportCandidate
import io.github.stream29.kodex.hook.contract.HookCodexImportIdentity
import io.github.stream29.kodex.hook.contract.HookCodexImportSource
import io.github.stream29.kodex.hook.contract.HookCodexImportSummary
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookConfigurationStore
import io.github.stream29.kodex.hook.contract.HookEnvironmentDraft
import io.github.stream29.kodex.hook.contract.HookEnvironmentValue
import io.github.stream29.kodex.hook.contract.HookImportDecision
import io.github.stream29.kodex.hook.contract.HookImportDisposition
import io.github.stream29.kodex.hook.contract.HookImportItem
import io.github.stream29.kodex.hook.contract.HookImportPreview
import io.github.stream29.kodex.hook.contract.HookImportSupport
import io.github.stream29.kodex.hook.contract.HookManagedSourceState
import io.github.stream29.kodex.hook.contract.HookManager
import io.github.stream29.kodex.hook.contract.HookMatcher
import io.github.stream29.kodex.hook.contract.HookSourceConfiguration
import io.github.stream29.kodex.hook.contract.HookSourceDraft
import io.github.stream29.kodex.hook.contract.HookSourceIdFactory
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Default application-wide [HookManager] implementation. */
public class HookManagerImpl internal constructor(
    scope: CoroutineScope,
    private val store: HookConfigurationStore,
    private val codexImportSource: HookCodexImportSource,
    private val sourceIdFactory: HookSourceIdFactory,
) : HookManager {
    private val scope = scope.supervisorChildScope()
    private val commandMutex = Mutex()
    private val mutableFeatureEnabled = MutableStateFlow(store.configuration.value.featureEnabled)
    private val mutableSources = MutableStateFlow(store.configuration.value.toManagedSources())
    private var nextPreviewId: Long = 1
    private var activePreview: RawHookImportPreview? = null
    private var closed = false

    override val featureEnabled: StateFlow<Boolean> = mutableFeatureEnabled.asStateFlow()
    override val sources: StateFlow<List<HookManagedSourceState>> = mutableSources.asStateFlow()

    init {
        this.scope.launch {
            store.configuration.collect { configuration ->
                mutableFeatureEnabled.value = configuration.featureEnabled
                mutableSources.value = configuration.toManagedSources()
            }
        }
    }

    override suspend fun setFeatureEnabled(enabled: Boolean) {
        command {
            store.update { current -> current.copy(featureEnabled = enabled) }
        }
    }

    override suspend fun add(draft: HookSourceDraft): String =
        command {
            val normalized = draft.normalize(existingEnvironment = null)
            var createdId = ""
            store.update { current ->
                createdId = createUniqueId(current)
                current.copy(
                    sources = current.sources + normalized.toConfiguration(
                        id = createdId,
                        importIdentity = null,
                    ),
                )
            }
            createdId
        }

    override suspend fun edit(sourceId: String, draft: HookSourceDraft) {
        command {
            require(sourceId.isNotBlank()) { "A Hook source id must not be blank." }
            val existing = store.configuration.value.sources
                .firstOrNull { source -> source.id == sourceId }
                ?: throw IllegalArgumentException("Hook source '$sourceId' does not exist.")
            draft.normalize(existing.environment)
            store.update { current ->
                val index = current.sources.indexOfFirst { source -> source.id == sourceId }
                require(index >= 0) { "Hook source '$sourceId' does not exist." }
                val latest = current.sources[index]
                val replacement = draft
                    .normalize(latest.environment)
                    .toConfiguration(
                        id = latest.id,
                        importIdentity = latest.importIdentity,
                    )
                current.copy(
                    sources = current.sources.toMutableList().apply {
                        set(index, replacement)
                    },
                )
            }
        }
    }

    override suspend fun delete(sourceId: String) {
        command {
            store.update { current ->
                require(current.sources.any { source -> source.id == sourceId }) {
                    "Hook source '$sourceId' does not exist."
                }
                current.copy(
                    sources = current.sources.filterNot { source -> source.id == sourceId },
                )
            }
        }
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        command {
            store.update { current ->
                val index = current.sources.indexOfFirst { source -> source.id == sourceId }
                require(index >= 0) { "Hook source '$sourceId' does not exist." }
                current.copy(
                    sources = current.sources.toMutableList().apply {
                        set(index, get(index).copy(enabled = enabled))
                    },
                )
            }
        }
    }

    override fun editorDraft(sourceId: String): HookSourceDraft? {
        if (closed) return null
        return store.configuration.value.sources
            .firstOrNull { source -> source.id == sourceId }
            ?.let { source ->
                HookSourceDraft(
                    name = source.name,
                    enabled = source.enabled,
                    environment = source.environment.keys.associateWith {
                        HookEnvironmentDraft.Keep
                    },
                    hooks = source.hooks,
                )
            }
    }

    override suspend fun previewCodexImport(filter: String): HookImportPreview =
        command {
            val imported = codexImportSource.read()
                .sortedBy { candidate -> candidate.identity.key }
            require(imported.map { candidate -> candidate.identity }.distinct().size == imported.size) {
                "Codex Hook import candidates must have unique source identities."
            }
            val normalizedFilter = filter.trim()
            val filtered = imported.filter { candidate ->
                normalizedFilter.isEmpty() ||
                    candidate.displayName.contains(normalizedFilter, ignoreCase = true) ||
                    candidate.identity.normalizedPath.contains(normalizedFilter, ignoreCase = true)
            }
            check(nextPreviewId < Long.MAX_VALUE) { "Hook import preview ids are exhausted." }
            val id = nextPreviewId++
            val existingIdentities = store.configuration.value.sources
                .mapNotNull(HookSourceConfiguration::importIdentity)
                .toSet()
            activePreview = RawHookImportPreview(
                id = id,
                candidates = filtered
                    .filterIsInstance<HookCodexImportCandidate.Supported>()
                    .associateBy { candidate -> candidate.identity.key },
            )
            HookImportPreview(
                id = id,
                filter = normalizedFilter,
                items = filtered.map { candidate ->
                    candidate.toImportItem(existingIdentities)
                },
            )
        }

    override suspend fun applyCodexImport(
        previewId: Long,
        decisions: Map<String, HookImportDecision>,
    ) {
        command {
            val preview = activePreview?.takeIf { candidate -> candidate.id == previewId }
                ?: throw IllegalArgumentException(
                    "Hook import preview $previewId is no longer active.",
                )
            require(decisions.keys.all(preview.candidates::containsKey)) {
                "Hook import decisions contain a source outside the active preview."
            }
            store.update { current ->
                val updated = current.sources.toMutableList()
                preview.candidates.forEach { (sourceKey, candidate) ->
                    val conflictIndex = updated.indexOfFirst { source ->
                        source.importIdentity == candidate.identity
                    }
                    when (decisions[sourceKey] ?: HookImportDecision.Skip) {
                        HookImportDecision.Skip -> Unit
                        HookImportDecision.Import -> {
                            require(conflictIndex < 0) {
                                "Codex Hook source '$sourceKey' now conflicts with existing settings."
                            }
                            updated += candidate.toConfiguration(createUniqueId(current, updated))
                        }

                        HookImportDecision.Replace -> {
                            require(conflictIndex >= 0) {
                                "Codex Hook source '$sourceKey' is no longer a replaceable conflict."
                            }
                            val existing = updated[conflictIndex]
                            updated[conflictIndex] = candidate.toConfiguration(existing.id)
                        }
                    }
                }
                current.copy(sources = updated)
            }
            activePreview = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        activePreview = null
        scope.cancel()
    }

    private suspend fun <Value> command(block: suspend () -> Value): Value {
        check(!closed) { "The Hook manager is closed." }
        return commandMutex.withLock { block() }
    }

    private fun createUniqueId(
        configuration: HookConfiguration,
        additional: List<HookSourceConfiguration> = emptyList(),
    ): String {
        val existing = configuration.sources.mapTo(mutableSetOf(), HookSourceConfiguration::id)
        additional.mapTo(existing, HookSourceConfiguration::id)
        repeat(MaximumHookSourceIdAttempts) {
            val candidate = sourceIdFactory.create().trim()
            if (candidate.isNotEmpty() && candidate !in existing) return candidate
        }
        throw IllegalStateException("A unique Hook source id could not be generated.")
    }
}

/** Creates a manager whose lifetime is a child of this scope. */
public fun CoroutineScope.HookManagerImpl(
    store: HookConfigurationStore,
    codexImportSource: HookCodexImportSource,
    sourceIdFactory: HookSourceIdFactory = DefaultHookSourceIdFactory,
): HookManagerImpl =
    HookManagerImpl(
        scope = this,
        store = store,
        codexImportSource = codexImportSource,
        sourceIdFactory = sourceIdFactory,
    )

private data class NormalizedHookSourceDraft(
    val name: String,
    val enabled: Boolean,
    val environment: Map<String, HookEnvironmentValue>,
    val hooks: io.github.stream29.kodex.hook.contract.HookDeclarations,
) {
    fun toConfiguration(
        id: String,
        importIdentity: HookCodexImportIdentity?,
    ): HookSourceConfiguration =
        HookSourceConfiguration(
            id = id,
            name = name,
            enabled = enabled,
            importIdentity = importIdentity,
            environment = environment,
            hooks = hooks,
        )
}

private fun HookSourceDraft.normalize(
    existingEnvironment: Map<String, HookEnvironmentValue>?,
): NormalizedHookSourceDraft {
    val normalizedName = name.trim()
    require(normalizedName.isNotEmpty()) { "A Hook source name must not be blank." }
    require(hooks.commandCount > 0) { "A Hook source must contain at least one command." }
    hooks.configuredEvents
        .flatMap(hooks::groups)
        .forEach { group ->
            require(group.matcher !is HookMatcher.Invalid) {
                "A Hook source cannot contain an invalid matcher."
            }
        }
    val normalizedEnvironment = buildMap {
        environment.forEach { (untrimmedName, draft) ->
            val name = untrimmedName.trim()
            require(name.isNotEmpty()) { "A Hook environment name must not be blank." }
            require(name !in this) { "Hook environment names must be unique." }
            val value = when (draft) {
                HookEnvironmentDraft.Keep ->
                    existingEnvironment?.get(name)
                        ?: throw IllegalArgumentException(
                            "Hook environment '$name' has no stored value to retain.",
                        )

                is HookEnvironmentDraft.Replace -> HookEnvironmentValue(draft.value)
            }
            put(name, value)
        }
    }
    return NormalizedHookSourceDraft(
        name = normalizedName,
        enabled = enabled,
        environment = normalizedEnvironment,
        hooks = hooks,
    )
}

private fun HookConfiguration.toManagedSources(): List<HookManagedSourceState> =
    sources.map { source ->
        HookManagedSourceState(
            sourceId = source.id,
            name = source.name,
            enabled = source.enabled,
            importedFrom = source.importIdentity?.let { identity ->
                HookCodexImportSummary(
                    sourceKind = identity.sourceKind,
                    normalizedPath = identity.normalizedPath,
                )
            },
            configuredEvents = source.hooks.configuredEvents,
            commandCount = source.hooks.commandCount,
            environmentNames = source.environment.keys.sorted(),
        )
    }

private fun HookCodexImportCandidate.toImportItem(
    existingIdentities: Set<HookCodexImportIdentity>,
): HookImportItem =
    when (this) {
        is HookCodexImportCandidate.Supported -> HookImportItem(
            sourceKey = identity.key,
            displayName = displayName,
            sourceKind = identity.sourceKind,
            normalizedPath = identity.normalizedPath,
            disposition = if (identity in existingIdentities) {
                HookImportDisposition.Conflict
            } else {
                HookImportDisposition.New
            },
            support = if (excludedDetails.isEmpty()) {
                HookImportSupport.Full
            } else {
                HookImportSupport.Partial
            },
            configuredEvents = template.hooks.configuredEvents,
            commandCount = template.hooks.commandCount,
            environmentNames = template.environment.keys.sorted(),
            excludedDetails = excludedDetails,
            selectable = true,
        )

        is HookCodexImportCandidate.Unsupported -> HookImportItem(
            sourceKey = identity.key,
            displayName = displayName,
            sourceKind = identity.sourceKind,
            normalizedPath = identity.normalizedPath,
            disposition = null,
            support = HookImportSupport.Unsupported,
            configuredEvents = emptyList(),
            commandCount = 0,
            environmentNames = emptyList(),
            excludedDetails = listOf(detail),
            selectable = false,
        )
    }

private fun HookCodexImportCandidate.Supported.toConfiguration(
    id: String,
): HookSourceConfiguration =
    HookSourceConfiguration(
        id = id,
        name = template.name,
        enabled = template.enabled,
        importIdentity = identity,
        environment = template.environment,
        hooks = template.hooks,
    )

private data class RawHookImportPreview(
    val id: Long,
    val candidates: Map<String, HookCodexImportCandidate.Supported>,
)

@OptIn(ExperimentalUuidApi::class)
private val DefaultHookSourceIdFactory: HookSourceIdFactory =
    HookSourceIdFactory { Uuid.generateV7().toString() }

private const val MaximumHookSourceIdAttempts: Int = 100
