@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.app.accountusage.AccountUsageViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel
import io.github.stream29.kodex.app.mcpsettings.McpSettingsViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsEffect
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.cli.newsession.NewSessionDraftPort
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlinx.rpc.RpcClient
import kotlin.time.Instant

/** Borrowed proxies from an already restoring client. This object owns no connection. */
public expect class RpcServices(client: RpcClient) {
    public val global: GlobalRpc
    public val runtime: AgentRuntimeRpc
}

/** Independent backend observations, not a locally writable combined settings snapshot. */
public expect class RpcGlobalSettings : AutoCloseable {
    public val settings: SuspendMutableStateFlow<BackendSettings>
    public val models: StateFlow<List<ModelInfo>>
    public val authentication: StateFlow<SettingsAuthenticationState>
    public val usage: StateFlow<SettingsAccountUsageState>
    public val mcp: StateFlow<List<McpManagedServerState>>
    public val sidebarWidths: StateFlow<Pair<Int, Int>>
    public fun ensureActive(): Unit

    /** Width changes are intentionally never persisted in either settings file. */
    public fun resizeSidebars(left: Int, right: Int)

    override fun close(): Unit

    public companion object {
        public suspend fun open(
            rpc: GlobalRpc,
            scope: CoroutineScope,
            applicationWidth: Int,
        ): RpcGlobalSettings
    }
}

/** Stable child composition and host ports; accepted writes use the original application queue. */
public expect class RpcGlobalEditor(
    global: RpcGlobalSettings,
    rpc: GlobalRpc,
    applicationScope: CoroutineScope,
    launchBaseDirectory: String,
) : GlobalSettingsViewModel {
    override val contextSourceSettings: ContextSourceSettingsViewModel
    override val sessionTitleSettings: SessionTitleSettingsViewModel
    override val applicationPreferences: ApplicationPreferencesViewModel
    override val authenticationSettings: AuthenticationSettingsViewModel
    override val accountUsage: AccountUsageViewModel
    override val usageReset: UsageResetViewModel
    override val mcpSettings: McpSettingsViewModel
    override val hookSettings: HookSettingsViewModel
    override val frontendErrorHooks: HookSettingsViewModel
    override val effects: Flow<GlobalSettingsEffect>
    override val operationFailure: StateFlow<Boolean>
    override fun dismissOperationFailure(): Unit
    override fun close(): Unit
}

/**
 * Host composition of a real defaults component and its independent application-scope write queue.
 * All projection, revisions and command admission belong to the component; this adapter owns only
 * field-specific backend writes and queue disposal. Closing the child cannot cancel accepted writes.
 */
public expect fun RpcNewSessionSettings(
    global: RpcGlobalSettings,
    applicationScope: CoroutineScope,
): NewSessionSettingsViewModel

/** Adapts Global RPC to the component's backend port without opening Session runtimes. */
public expect class RpcSessionCatalogDependencies(
    global: GlobalRpc,
    deleteSession: suspend (Int) -> Boolean = global::deleteSession,
) : SessionCatalogDependencies {
    override suspend fun load(showArchived: Boolean): List<SessionCatalogEntry>
    override suspend fun archive(sessionIndex: Int): Unit
    override suspend fun unarchive(sessionIndex: Int): Unit
    override suspend fun fork(sessionIndex: Int): Int
    override suspend fun delete(sessionIndex: Int): Boolean
}

/** A disposable frontend read/command binding. It is neither AgentRuntime nor AgentState. */
public expect class RpcSessionBinding {
    public val sessionIndex: Int
    public val storage: ObservableKodexAgentStorage
    public val latestIndex: StateFlow<Int>
    public val state: StateFlow<AgentStateValue>
    public val displayState: StateFlow<KodexAgentStateValue>
    public val running: StateFlow<Boolean>
    public val shellSessions: StateFlow<Map<Int, ShellSessionState>>
    public val settings: SuspendMutableStateFlow<KodexAgentSettings>
    public val pendingSteer: StateFlow<List<AgentCommit.Steerable>>
    public val tokenCount: StateFlow<LlmRequestMeta?>
    public fun ensureActive(): Unit
    public suspend fun submit(content: List<ContentItem>): Unit
    public suspend fun resume(): Unit
    public suspend fun stop(): Unit
    public suspend fun forcedCompact(): Int
    public suspend fun clearPending(): Int
    public suspend fun submitAskUserAnswer(askId: Int, response: RequestUserInputResponse): Unit
    public suspend fun closeShellSession(id: Int): Unit
    public suspend fun revertHistory(untilExclusive: Int, selectedCacheNonce: Long): Unit
    public suspend fun forkHistory(untilExclusive: Int, selectedCacheNonce: Long): Int
    public suspend fun readCreatedAt(): Instant?
    public suspend fun readUpdatedAt(): Instant?
}

/** Local availability, not a remote execution state or a synthetic Agent lifecycle. */
public sealed interface SessionViewStatus {
    public data object Loading : SessionViewStatus
    public data object Ready : SessionViewStatus
    public data object Missing : SessionViewStatus
    public data class Failed(public val cause: Throwable) : SessionViewStatus
    public data object Closed : SessionViewStatus
}

/**
 * One view/heartbeat per opened tab index; a directory lookup does not create a view.
 * [reportUnhandledError] is the application-owned local error outlet, retained across rebinding.
 * Backend-reported run failures and cancellation never enter it.
 */
public expect class RpcSessionViews(
    scope: CoroutineScope,
    services: RpcServices,
    models: StateFlow<List<ModelInfo>>,
    reportUnhandledError: (Throwable) -> Unit,
    connectionAvailable: () -> Boolean = { true },
) : PersistedSessionViewModelRegistry, AutoCloseable {
    public val services: RpcServices
    override suspend fun open(sessionIndex: Int): RpcSessionView
    public suspend fun release(view: RpcSessionView): Unit

    /** Also cancels a tab that is still initializing and has not returned a view to its caller. */
    override suspend fun release(sessionIndex: Int): Unit
    override suspend fun archive(sessionIndex: Int): Unit
    override suspend fun unarchive(sessionIndex: Int): Unit
    override suspend fun fork(sessionIndex: Int): Int
    override suspend fun delete(sessionIndex: Int): Boolean
    override suspend fun shutdown(): Unit

    /** Reconnect activates still-open frontend tabs before publishing the new connection. */
    public suspend fun reactivate(replacement: RpcServices, isConnected: () -> Boolean): Unit

    override fun close(): Unit
    public suspend fun join(): Unit
}

/**
 * The coordinator outlives an individual subscription binding, not the frontend owner.
 * Recovery only repeats activation/reads; no failed or unacknowledged command is replayed.
 */
public expect class RpcSessionView internal constructor(
    sessionIndex: Int,
    services: RpcServices,
    scope: CoroutineScope,
    models: StateFlow<List<ModelInfo>>,
    reportUnhandledError: (Throwable) -> Unit,
    connectionAvailable: () -> Boolean,
) : PersistedSessionViewModel {
    override val sessionIndex: Int
    public val binding: StateFlow<RpcSessionBinding?>
    public val status: StateFlow<SessionViewStatus>
    /** Sole binding-owned Agent; recovery replaces it rather than wrapping the previous owner. */
    override val rootAgent: StateFlow<AgentViewModel?>
    override val settings: StateFlow<KodexAgentSettings>
    override val models: StateFlow<List<ModelInfo>>
    override val name: StateFlow<String>
    override val lifecycle: StateFlow<PersistedSessionLifecycleState>

    public fun current(): RpcSessionBinding
    override suspend fun readCreatedAt(): Instant?
    override suspend fun readUpdatedAt(): Instant?
    override suspend fun fork(
        source: AgentViewModel,
        untilExclusive: Int,
        expectedGeneration: Long,
    ): Int
    override suspend fun rename(name: String): Unit
    override suspend fun updateModel(model: OpenAiModelId): Unit
    override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit
    override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit
    override suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ): Unit
    override suspend fun shutdown(): Unit
    override fun close(): Unit
    public suspend fun join(): Unit
}

/**
 * Opening or editing this object does not allocate a backend Session.
 * Its original local owner is a child of [ownerScope]; parent cancellation/join
 * closes Composer and its observations. Explicit close cancels only this child.
 * Views, global observations and the shared backend are borrowed, never closed or stopped here.
 * Submission runs in the host's surviving command owner; this object adds no detached work
 * or secondary error outlet.
 */
public expect class RpcSessionDraft(
    initialWorkingDirectory: Path,
    views: RpcSessionViews,
    global: RpcGlobalSettings,
    ownerScope: CoroutineScope,
) : NewSessionDraftPort {
    override val settings: StateFlow<KodexAgentSettings>
    override val composer: ComposerViewModel

    /** Freeze only this intent's target fields, preserving unrelated fields on CAS retry. */
    override suspend fun <F> editDefaults(
        select: (KodexNewSessionSettings) -> F,
        replace: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    ): Unit

    override fun updateWorkingDirectory(path: Path): Unit

    /**
     * [open] opens the host's stable persisted handle before any submit; RPC-only consumers may
     * supply [RpcSessionViews.open]. Creation and submit remain distinct commits. The caller
     * owns accepted work and errors, so this suspends directly rather than launching hidden work.
     */
    override suspend fun <T> submit(expectedRevision: Long, open: suspend (Int) -> T): T?

    override fun close(): Unit
}

/** A popup targets this exact binding; reactivation cannot silently retarget an old editor. */
public expect class RpcSessionSettingsSource(
    view: RpcSessionView,
    scope: CoroutineScope,
) : SessionSettingsDataSource {
    override val state: StateFlow<SessionSettingsDataState>
    override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean
    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean
    override fun close(): Unit
}

/**
 * No persisted target was captured. Never resolves a selected tab or supplies editable defaults.
 * Stateless across popup owners: disposal is a no-op and cannot affect another popup.
 */
public expect object UnavailableSessionSettingsDataSource : SessionSettingsDataSource {
    override val state: StateFlow<SessionSettingsDataState>
    override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean
    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean
    override fun close(): Unit
}
