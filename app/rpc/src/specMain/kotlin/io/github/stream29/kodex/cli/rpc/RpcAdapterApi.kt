@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.app.accountusage.AccountUsageViewModel
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerCancellationPort
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.ComposerResumePort
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel
import io.github.stream29.kodex.app.mcpsettings.McpImportHandle
import io.github.stream29.kodex.app.mcpsettings.McpSettingsViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsEffect
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsState
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.client.FrontendSettingsAccess
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.contract.IndexTimelineRpc
import io.github.stream29.kodex.rpc.contract.SettingsTimelineRpc
import io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow
import io.github.stream29.kodex.rpc.contract.TimestampTimelineRpc
import io.github.stream29.kodex.rpc.contract.TokenCountTimelineRpc
import io.github.stream29.kodex.rpc.contract.UnstableTimelineRpc
import io.github.stream29.kodex.rpc.contract.WorkTimelineRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.rpc.models.ShellSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlinx.rpc.RpcClient
import kotlin.time.Instant

/**
 * Creates the sole Agent ViewModel owned by this binding's frontend scope.
 */
public expect fun createRpcAgentViewModel(
    binding: RpcSessionBinding,
    scope: CoroutineScope,
    services: RpcServices,
    models: StateFlow<List<ModelInfo>>,
    onCreated: (List<CreatedSuggestedSession>) -> Unit,
    reportUnhandledError: (Throwable) -> Unit,
): AgentViewModel

/**
 * Creates the Composer child bound to one exact RPC Session binding.
 * Command callbacks must belong to that binding's presentation owner: resume/cancel admit
 * work through its caught command boundary, and failures use the Application's error outlet.
 * No callback may resolve the currently selected Session or silently discard a failure.
 */
public expect fun createRpcComposerViewModel(
    binding: RpcSessionBinding,
    ownerScope: CoroutineScope,
    resumePort: ComposerResumePort,
    cancellationPort: ComposerCancellationPort,
    failureReporter: ComposerFailureReporter,
): ComposerViewModel

/** Bind once to an exact Agent or draft. No active-session lookup or settings mirror is introduced. */
public expect fun createBoundRuntimeConfigurationViewModel(
    target: AgentSettingsViewModel,
    ownerScope: CoroutineScope,
): RuntimeConfigurationViewModel

/**
 * One editing intent keeps its target's initial value. Only false is retried; a lost response,
 * cancellation, or expired owner is never interpreted as permission to replay a write.
 */
public expect suspend fun <T, F> SuspendMutableStateFlow<T>.editField(
    initial: F,
    select: (T) -> F,
    replace: (T) -> T,
    checkOwner: () -> Unit = {},
): Boolean

/** Browser URLs and callback payloads live only for the attempt; neither is logged. */
public expect suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
    mcpRedirectUri: String? = null,
): KodexAuthLoginAttempt

/** Borrowed proxies from an already restoring client. This object owns no connection. */
public expect class RpcServices(client: RpcClient) {
    public val global: GlobalRpc
    public val runtime: AgentRuntimeRpc
    public val index: IndexTimelineRpc
    public val work: WorkTimelineRpc
    public val settings: SettingsTimelineRpc
    public val timestamp: TimestampTimelineRpc
    public val tokenCount: TokenCountTimelineRpc
    public val unstable: UnstableTimelineRpc
}

/** Independent backend observations, not a locally writable combined settings snapshot. */
public expect class RpcGlobalSettings : AutoCloseable {
    public val settings: SuspendMutableStateFlow<BackendSettings>
    public val models: StateFlow<List<ModelInfo>>
    public val authentication: StateFlow<SettingsAuthenticationState>
    public val usage: StateFlow<SettingsAccountUsageState>
    public val mcp: StateFlow<List<McpManagedServerState>>
    public val frontend: FrontendSettingsAccess
    public val sidebarWidths: StateFlow<Pair<Int, Int>>
    public fun ensureActive(): Unit

    public suspend fun <F> edit(
        initial: F,
        select: (BackendSettings) -> F,
        replace: (BackendSettings) -> BackendSettings,
    ): Boolean

    /** Width changes are intentionally never persisted in either settings file. */
    public fun resizeSidebars(left: Int, right: Int)

    public suspend fun updateHooks(hooks: List<NotificationHook>)

    override fun close(): Unit
    public suspend fun join(): Unit

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
) : GlobalSettingsViewModel {
    public val global: RpcGlobalSettings
    public val rpc: GlobalRpc
    public val scope: CoroutineScope
    public val mcp: RpcMcpSettings

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

    public fun createLogin(ownerScope: CoroutineScope = scope): OpenAiLoginViewModel

    override fun close(): Unit
}

/** A local editor keeps full configuration only in memory; list/detail rendering is sanitized. */
public expect class RpcMcpSettings(
    global: RpcGlobalSettings,
    rpc: GlobalRpc,
    scope: CoroutineScope,
) : AutoCloseable {
    public val servers: StateFlow<List<McpServerSettingsState>>

    /** Capture before opening a delayed dialog, not when its submit coroutine finally runs. */
    public fun capture(): Map<String, McpServerConfiguration>

    public suspend fun save(
        captured: Map<String, McpServerConfiguration>,
        originalName: String?,
        draft: McpServerDraft,
    ): Boolean

    public suspend fun delete(name: String, captured: McpServerConfiguration): Boolean
    public suspend fun setEnabled(name: String, captured: McpServerConfiguration, enabled: Boolean): Boolean
    public suspend fun readImport(accept: (suspend () -> Unit) -> Boolean): McpImportHandle
    public suspend fun reconnect(name: String): Unit
    public suspend fun logout(name: String): Unit
    override fun close(): Unit
}

/**
 * Host composition of a real defaults component and its independent application-scope write queue.
 * All projection, revisions and command admission belong to the component; this adapter owns only
 * field-specific backend writes and queue disposal. Closing the child cannot cancel accepted writes.
 */
public expect class RpcNewSessionSettings : NewSessionSettingsViewModel {
    public constructor(global: RpcGlobalSettings, applicationScope: CoroutineScope)
    override val state: StateFlow<NewSessionSettingsState>
    override val operationFailure: StateFlow<Boolean>
    override fun updateModel(expectedRevision: Long, model: OpenAiModelId): Unit
    override fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort): Unit
    override fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier): Unit
    override fun updateRequestUserInputMode(expectedRevision: Long, mode: RequestUserInputMode): Unit
    override fun dismissOperationFailure(): Unit
    override fun close(): Unit
}

/** Adapts Global RPC to the component's backend port without opening Session runtimes. */
public expect class RpcSessionCatalogDependencies(
    global: GlobalRpc,
    deleteSession: suspend (Int) -> Boolean = global::deleteSession,
    forkSession: suspend (Int) -> Int = global::forkSession,
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
    public val pendingSteer: SuspendMutableStateFlow<List<StableIndexEvent.Steerable>>
    public val tokenCount: StateFlow<TokenCountSnapshot?>
    public fun ensureActive(): Unit
    public suspend fun appendUserMessage(content: List<ContentItem>): Int
    public suspend fun resume(): Unit
    public suspend fun stop(): Unit
    public suspend fun forcedCompact(): Int
    public suspend fun clearPending(): Int
    public suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int
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
    onCreated: (List<CreatedSuggestedSession>) -> Unit = {},
) : AutoCloseable {
    public val services: RpcServices
    public suspend fun open(index: Int): RpcSessionView
    public suspend fun release(view: RpcSessionView): Unit

    /** Also cancels a tab that is still initializing and has not returned a view to its caller. */
    public suspend fun release(index: Int): Unit

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
    index: Int,
    services: RpcServices,
    scope: CoroutineScope,
    models: StateFlow<List<ModelInfo>>,
    onCreated: (List<CreatedSuggestedSession>) -> Unit,
    reportUnhandledError: (Throwable) -> Unit,
    connectionAvailable: () -> Boolean,
) : AutoCloseable {
    public val index: Int
    public val binding: StateFlow<RpcSessionBinding?>
    public val status: StateFlow<SessionViewStatus>
    /** Sole binding-owned Agent; recovery replaces it rather than wrapping the previous owner. */
    public val agent: StateFlow<AgentViewModel?>

    public fun current(): RpcSessionBinding
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
) : AutoCloseable {
    public val settings: StateFlow<KodexAgentSettings>
    public val composer: ComposerViewModel

    /** Freeze only this intent's target fields, preserving unrelated fields on CAS retry. */
    public suspend fun <F> editDefaults(
        select: (KodexNewSessionSettings) -> F,
        replace: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    ): Unit

    public fun updateWorkingDirectory(path: Path): Unit

    /**
     * [open] opens the host's stable persisted handle before any append; RPC-only consumers may
     * supply [RpcSessionViews.open]. Creation and append remain distinct commits. The caller
     * owns accepted work and errors, so this suspends directly rather than launching hidden work.
     */
    public suspend fun <T> submit(expectedRevision: Long, open: suspend (Int) -> T): T?

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
