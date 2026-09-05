package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsEffect
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsState
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.cli.auth.KodexAuthStore
import io.github.stream29.kodex.cli.sessiontitle.DefaultSessionTitleModel
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.hook.contract.HookDraft
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.hook.contract.HookManager
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpManager
import io.github.stream29.kodex.mcp.contract.McpManagerEffect
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageStore
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetAttempt
import io.github.stream29.kodex.openai.accountusage.snapshotOrNull
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

internal class GlobalSettingsViewModelImpl(
    private val globalSettings: KodexGlobalSettingsStore,
    private val authenticationStore: KodexAuthStore,
    private val accountUsageStore: CodexAccountUsageStore,
    private val mcpManager: McpManager,
    private val hookManager: HookManager,
    models: StateFlow<List<ModelInfo>>,
    private val commandScope: CoroutineScope,
    private val reportUnhandledError: ((Throwable) -> Unit)? = null,
) : GlobalSettingsViewModel {
    private data class PreparedReset(
        val option: UsageResetOption,
        val attempt: CodexRateLimitResetAttempt,
    )

    private val scope = commandScope.supervisorChildScope()
    private val updates = SettingsUpdateQueue(commandScope, defaultReportError = reportUnhandledError)
    private val effectChannel = Channel<GlobalSettingsEffect>(Channel.BUFFERED)
    private var closed: Boolean = false
    private var latestSettings: KodexGlobalSettings = globalSettings.settings.value
    private var settingsRevision: Long = 0
    private var resetGeneration: Long = 0
    private var resetRequest: UsageResetRequest? = null
    private var preparedReset: PreparedReset? = null

    private val mutableState = MutableStateFlow(
        latestSettings.toGlobalSettingsState(
            revision = settingsRevision,
            models = models.value,
        ),
    )
    private val mutableAuthentication =
        MutableStateFlow(authenticationStore.state.value.toSettingsState())
    private val mutableAuthenticationOperation =
        MutableStateFlow<SettingsAuthenticationOperationState>(
            SettingsAuthenticationOperationState.Idle,
        )
    private val mutableAccountUsage = MutableStateFlow(
        accountUsageStore.state.value.toSettingsState(),
    )
    private val mutableMcpServers = MutableStateFlow<List<McpServerSettingsState>>(emptyList())
    private val mutableMcpImportPreview = MutableStateFlow<McpImportPreview?>(null)
    private val mutableUsageReset = MutableStateFlow<UsageResetState>(UsageResetState.Hidden)
    override val state: StateFlow<GlobalSettingsState> = mutableState.asStateFlow()
    override val authentication: StateFlow<SettingsAuthenticationState> =
        mutableAuthentication.asStateFlow()
    override val authenticationOperation: StateFlow<SettingsAuthenticationOperationState> =
        mutableAuthenticationOperation.asStateFlow()
    override val accountUsage: StateFlow<SettingsAccountUsageState> =
        mutableAccountUsage.asStateFlow()
    override val mcpServers: StateFlow<List<McpServerSettingsState>> =
        mutableMcpServers.asStateFlow()
    override val mcpImportPreview: StateFlow<McpImportPreview?> =
        mutableMcpImportPreview.asStateFlow()
    override val hooks: StateFlow<List<HookManagedState>> = hookManager.hooks
    override val usageReset: StateFlow<UsageResetState> = mutableUsageReset.asStateFlow()
    override val effects: Flow<GlobalSettingsEffect> = effectChannel.receiveAsFlow()

    init {
        scope.launch {
            combine(globalSettings.settings, models) { settings, modelCatalog ->
                settings to modelCatalog
            }.collect { (settings, modelCatalog) ->
                if (settings != latestSettings) {
                    check(settingsRevision < Long.MAX_VALUE) {
                        "Global Settings revisions are exhausted."
                    }
                    latestSettings = settings
                    settingsRevision += 1
                }
                mutableState.value = settings.toGlobalSettingsState(
                    revision = settingsRevision,
                    models = modelCatalog,
                )
            }
        }
        scope.launch {
            authenticationStore.state.collect { authState ->
                mutableAuthentication.value = authState.toSettingsState()
            }
        }
        scope.launch {
            mcpManager.servers.collect { servers ->
                mutableMcpServers.value = servers.map(McpManagedServerState::toSettingsState)
            }
        }
        scope.launch {
            mcpManager.effects.collect { effect ->
                when (effect) {
                    is McpManagerEffect.OpenAuthorizationUrl ->
                        effectChannel.send(
                            GlobalSettingsEffect.OpenMcpAuthorizationUrl(
                                serverName = effect.serverName,
                                url = effect.url,
                            ),
                        )
                }
            }
        }
        scope.launch {
            accountUsageStore.state.collect { usage ->
                mutableAccountUsage.value = usage.toSettingsState()
                if (
                    usage.snapshotOrNull() == null &&
                    mutableUsageReset.value !is UsageResetState.Hidden
                ) {
                    dismissUsageReset()
                }
            }
        }
    }

    override fun setBuiltInContextSourceEnabled(
        source: BuiltInContextSource,
        enabled: Boolean,
    ) {
        enqueueSettingsUpdate {
            copy(contextSources = contextSources.withBuiltIn(source, enabled))
        }
    }

    override fun addCustomContextSource(path: String): String? {
        val input = path.trim()
        val normalized = input.toContextPath()?.toString()
            ?: return "Enter an absolute path, ~, or ~/path."
        if (normalized in staticContextSourcePaths()) {
            return "This path is already a built-in context source."
        }
        val current = latestSettings.contextSources
        val duplicate = current.customSources.indexOfFirst { source ->
            source.path.toContextPath()?.toString() == normalized
        }
        if (duplicate >= 0) {
            if (!current.customSources[duplicate].enabled) {
                enqueueSettingsUpdate {
                    copy(
                        contextSources = contextSources.copy(
                            customSources = contextSources.customSources.mapIndexed { index, source ->
                                if (index == duplicate) source.copy(enabled = true) else source
                            },
                        ),
                    )
                }
            }
            return null
        }
        enqueueSettingsUpdate {
            copy(
                contextSources = contextSources.copy(
                    customSources = contextSources.customSources +
                        AgentContextCustomSource(path = input),
                ),
            )
        }
        return null
    }

    override fun setCustomContextSourceEnabled(path: String, enabled: Boolean) {
        enqueueSettingsUpdate {
            copy(
                contextSources = contextSources.copy(
                    customSources = contextSources.customSources.map { source ->
                        if (source.path == path) source.copy(enabled = enabled) else source
                    },
                ),
            )
        }
    }

    override fun removeCustomContextSource(path: String) {
        enqueueSettingsUpdate {
            copy(
                contextSources = contextSources.copy(
                    customSources = contextSources.customSources.filterNot { source ->
                        source.path == path
                    },
                ),
            )
        }
    }

    override fun updateNewLineKey(newLineKey: NewLineKey) {
        enqueueSettingsUpdate {
            copy(newLineKey = newLineKey)
        }
    }

    override fun updateAuthSource(authSource: KodexAuthSource) {
        if (mutableAuthenticationOperation.value.isRunning()) return
        dismissUsageReset()
        enqueueSettingsUpdate {
            copy(authSource = authSource)
        }
    }

    override fun updateSessionTitleEnabled(enabled: Boolean) {
        enqueueSettingsUpdate {
            copy(sessionTitle = sessionTitle.copy(enabled = enabled))
        }
    }

    override fun updateSessionTitleModel(model: OpenAiModelId) {
        enqueueSettingsUpdate {
            copy(sessionTitle = sessionTitle.copy(model = model))
        }
    }

    override fun updateSessionTitleReasoningEffort(reasoningEffort: ReasoningEffort) {
        enqueueSettingsUpdate {
            copy(sessionTitle = sessionTitle.copy(reasoningEffort = reasoningEffort))
        }
    }

    override fun updateLeftSidebarWidth(columns: Int) {
        enqueueSettingsUpdate {
            copy(sidebars = sidebars.copy(leftWidth = columns))
        }
    }

    override fun updateRightSidebarWidth(columns: Int) {
        enqueueSettingsUpdate {
            copy(sidebars = sidebars.copy(rightWidth = columns))
        }
    }

    override fun requestLogin() {
        if (
            closed ||
            mutableState.value.authSource != KodexAuthSource.Kodex ||
            mutableAuthenticationOperation.value.isRunning()
        ) {
            return
        }
        dismissUsageReset()
        mutableAuthenticationOperation.value = SettingsAuthenticationOperationState.Idle
        effectChannel.trySend(GlobalSettingsEffect.OpenLogin)
    }

    override fun reloadAuthentication() {
        launchAuthenticationOperation(
            running = SettingsAuthenticationOperationState.Reloading,
            operation = SettingsAuthenticationOperation.Reload,
            command = authenticationStore::reload,
        )
    }

    override fun logoutKodex() {
        if (
            mutableState.value.authSource != KodexAuthSource.Kodex ||
            mutableAuthentication.value !is SettingsAuthenticationState.Authenticated
        ) {
            return
        }
        dismissUsageReset()
        launchAuthenticationOperation(
            running = SettingsAuthenticationOperationState.SigningOut,
            operation = SettingsAuthenticationOperation.Logout,
            command = authenticationStore::logoutKodex,
        )
    }

    override fun dismissAuthenticationOperationFailure() {
        if (mutableAuthenticationOperation.value is SettingsAuthenticationOperationState.Failed) {
            mutableAuthenticationOperation.value = SettingsAuthenticationOperationState.Idle
        }
    }

    override fun refreshUsage() {
        if (closed) return
        when (accountUsageStore.state.value) {
            is CodexAccountUsageState.Loading,
            is CodexAccountUsageState.Redeeming,
                -> return

            is CodexAccountUsageState.Available,
            is CodexAccountUsageState.Failed,
            CodexAccountUsageState.Unavailable,
                -> Unit
        }
        commandScope.launch {
            try {
                accountUsageStore.refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The account-usage store publishes its own typed failure state.
            }
        }
    }

    override fun requestUsageReset() {
        if (closed) return
        val request = (accountUsageStore.state.value as? CodexAccountUsageState.Available)
            ?.snapshot
            ?.usageResetRequestOrNull()
            ?: return
        invalidateResetOperation()
        resetRequest = request
        preparedReset = null
        mutableUsageReset.value = UsageResetState.Choosing(request)
    }

    override fun selectUsageReset(option: UsageResetOption) {
        val choosing = mutableUsageReset.value as? UsageResetState.Choosing ?: return
        if (option !in choosing.request.options) return
        val generation = nextResetGeneration()
        resetRequest = choosing.request
        preparedReset = null
        mutableUsageReset.value = UsageResetState.Preparing(option)
        scope.launch {
            try {
                val attempt = accountUsageStore.createResetAttempt(option.creditId)
                if (
                    resetGeneration == generation &&
                    mutableUsageReset.value == UsageResetState.Preparing(option)
                ) {
                    preparedReset = PreparedReset(option, attempt)
                    mutableUsageReset.value = UsageResetState.Confirming(option)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (resetGeneration == generation) {
                    preparedReset = null
                    mutableUsageReset.value = UsageResetState.PreparationFailed
                }
            }
        }
    }

    override fun returnToUsageResetChoices() {
        if (mutableUsageReset.value !is UsageResetState.Confirming) return
        val request = resetRequest ?: return
        invalidateResetOperation()
        preparedReset = null
        mutableUsageReset.value = UsageResetState.Choosing(request)
    }

    override fun confirmUsageReset() {
        val confirming = mutableUsageReset.value as? UsageResetState.Confirming ?: return
        val prepared = preparedReset?.takeIf { it.option == confirming.option } ?: return
        consumeReset(prepared)
    }

    override fun retryUsageReset() {
        val failed = mutableUsageReset.value as? UsageResetState.ConsumeFailed ?: return
        val prepared = preparedReset?.takeIf { it.option == failed.option } ?: return
        consumeReset(prepared)
    }

    override fun dismissUsageReset() {
        invalidateResetOperation()
        resetRequest = null
        preparedReset = null
        mutableUsageReset.value = UsageResetState.Hidden
    }

    override fun reconnectMcpServer(serverName: String) {
        launchManagementCommand { mcpManager.reconnect(serverName) }
    }

    override fun addMcpServer(draft: McpServerDraft) {
        launchManagementCommand { mcpManager.add(draft) }
    }

    override fun editMcpServer(existingServerName: String, draft: McpServerDraft) {
        launchManagementCommand { mcpManager.edit(existingServerName, draft) }
    }

    override fun deleteMcpServer(serverName: String) {
        launchManagementCommand { mcpManager.delete(serverName) }
    }

    override fun setMcpServerEnabled(serverName: String, enabled: Boolean) {
        launchManagementCommand { mcpManager.setEnabled(serverName, enabled) }
    }

    override fun loginMcpServer(serverName: String) {
        launchManagementCommand { mcpManager.login(serverName) }
    }

    override fun cancelMcpServerLogin(serverName: String) {
        launchManagementCommand { mcpManager.cancelLogin(serverName) }
    }

    override fun logoutMcpServer(serverName: String) {
        launchManagementCommand { mcpManager.logout(serverName) }
    }

    override fun previewCodexMcpImport(filter: String) {
        launchManagementCommand {
            mutableMcpImportPreview.value = mcpManager.previewCodexImport(filter)
        }
    }

    override fun applyCodexMcpImport(
        previewId: Long,
        decisions: Map<String, McpImportDecision>,
    ) {
        launchManagementCommand {
            mcpManager.applyCodexImport(previewId, decisions)
            mutableMcpImportPreview.value = null
        }
    }

    override fun dismissCodexMcpImport() {
        mutableMcpImportPreview.value = null
    }

    override fun addHook(draft: HookDraft) {
        launchManagementCommand { hookManager.add(draft) }
    }

    override fun editHook(name: String, draft: HookDraft) {
        launchManagementCommand { hookManager.edit(name, draft) }
    }

    override fun deleteHook(name: String) {
        launchManagementCommand { hookManager.delete(name) }
    }

    override fun hookEditorDraft(name: String): HookDraft? = hookManager.editorDraft(name)

    override fun close() {
        if (closed) return
        closed = true
        dismissUsageReset()
        dismissCodexMcpImport()
        updates.close()
        effectChannel.close()
        scope.cancel()
    }

    internal fun onVisible() {
        refreshUsage()
    }

    private fun enqueueSettingsUpdate(
        transform: KodexGlobalSettings.() -> KodexGlobalSettings,
    ) {
        if (closed) return
        updates.submit {
            globalSettings.update { current -> current.transform() }
        }
    }

    private fun launchManagementCommand(command: suspend () -> Unit) {
        if (closed) return
        commandScope.launch {
            try {
                command()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Manager commands validate before any atomic settings update.
                reportUnhandledError?.invoke(failure)
            }
        }
    }

    private fun launchAuthenticationOperation(
        running: SettingsAuthenticationOperationState,
        operation: SettingsAuthenticationOperation,
        command: suspend () -> Unit,
    ) {
        if (closed || mutableAuthenticationOperation.value.isRunning()) return
        mutableAuthenticationOperation.value = running
        commandScope.launch {
            try {
                command()
                if (!closed) {
                    mutableAuthenticationOperation.value =
                        SettingsAuthenticationOperationState.Idle
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AuthenticationLogger.warn(failure) {
                    "OpenAI authentication operation failed ($operation)."
                }
                if (!closed) {
                    mutableAuthenticationOperation.value =
                        SettingsAuthenticationOperationState.Failed(operation)
                }
            }
        }
    }

    private fun consumeReset(prepared: PreparedReset) {
        val generation = nextResetGeneration()
        mutableUsageReset.value = UsageResetState.Consuming(prepared.option)
        commandScope.launch {
            try {
                val outcome = accountUsageStore.consumeResetAttempt(prepared.attempt)
                if (
                    resetGeneration == generation &&
                    mutableUsageReset.value == UsageResetState.Consuming(prepared.option)
                ) {
                    preparedReset = null
                    mutableUsageReset.value = UsageResetState.Completed(
                        outcome = outcome,
                        selectedCredit = prepared.option.creditId != null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (resetGeneration == generation) {
                    mutableUsageReset.value = UsageResetState.ConsumeFailed(prepared.option)
                }
            }
        }
    }

    private fun nextResetGeneration(): Long {
        check(resetGeneration < Long.MAX_VALUE) {
            "Usage-reset operation generations are exhausted."
        }
        resetGeneration += 1
        return resetGeneration
    }

    private fun invalidateResetOperation() {
        nextResetGeneration()
    }

}

private fun KodexGlobalSettings.toGlobalSettingsState(
    revision: Long,
    models: List<ModelInfo>,
): GlobalSettingsState {
    val effectiveTitleModel = sessionTitle.model ?: DefaultSessionTitleModel
    val modelOptions = (
        models.map(ModelInfo::slug) +
            effectiveTitleModel
        ).distinct()
    return GlobalSettingsState(
        settingsRevision = revision,
        authSource = authSource,
        newLineKey = newLineKey,
        contextSources = contextSources,
        sessionTitle = sessionTitle,
        sidebars = sidebars,
        effectiveSessionTitleModel = effectiveTitleModel,
        modelOptions = modelOptions,
    )
}

private fun AgentContextSourceSettings.withBuiltIn(
    source: BuiltInContextSource,
    enabled: Boolean,
): AgentContextSourceSettings =
    when (source) {
        BuiltInContextSource.AgentsHome -> copy(agentsHomeEnabled = enabled)
        BuiltInContextSource.KodexHome -> copy(kodexHomeEnabled = enabled)
        BuiltInContextSource.CodexHome -> copy(codexHomeEnabled = enabled)
        BuiltInContextSource.GitRoot -> copy(gitRootEnabled = enabled)
        BuiltInContextSource.WorkingDirectory -> copy(workingDirectoryEnabled = enabled)
    }

private fun String.toContextPath(): Path? {
    if (isBlank() || contains('$')) return null
    val home = Path(requireUserHomeDirectory())
    return when {
        this == "~" -> home
        startsWith("~/") -> Path(home, substring(2))
        else -> Path(this).takeIf(Path::isAbsolute)
    }
}

private fun staticContextSourcePaths(): Set<String> {
    val home = Path(requireUserHomeDirectory())
    return setOf(
        Path(home, ".agents").toString(),
        Path(home, ".kodex").toString(),
        Path(home, ".codex").toString(),
    )
}

private fun OpenAiAuthState.toSettingsState(): SettingsAuthenticationState =
    when (this) {
        is OpenAiAuthState.Authenticated -> SettingsAuthenticationState.Authenticated(
            accountId = credentials.accountId?.takeIf(String::isNotBlank),
            planType = credentials.planType,
            email = credentials.email?.takeIf(String::isNotBlank),
        )

        is OpenAiAuthState.Unavailable -> SettingsAuthenticationState.Unavailable(this)
    }

private fun SettingsAuthenticationOperationState.isRunning(): Boolean =
    this === SettingsAuthenticationOperationState.Reloading ||
        this === SettingsAuthenticationOperationState.SigningOut

private val AuthenticationLogger = KotlinLogging.logger {}.global()

private fun CodexAccountUsageState.toSettingsState(): SettingsAccountUsageState =
    when (this) {
        CodexAccountUsageState.Unavailable -> SettingsAccountUsageState.Unavailable
        is CodexAccountUsageState.Loading -> SettingsAccountUsageState.Loading(previous)
        is CodexAccountUsageState.Available -> SettingsAccountUsageState.Available(snapshot)
        is CodexAccountUsageState.Failed -> SettingsAccountUsageState.Failed(
            message = message,
            previous = previous,
        )

        is CodexAccountUsageState.Redeeming -> SettingsAccountUsageState.Redeeming(snapshot)
    }

private fun McpManagedServerState.toSettingsState(): McpServerSettingsState =
    McpServerSettingsState(
        serverName = serverName,
        transport = transport,
        enabled = enabled,
        authentication = authentication,
        status = connection.let { currentConnection ->
            when {
                !enabled -> McpServerSettingsStatus.Disabled
                authentication.isBlocked() ->
                    McpServerSettingsStatus.AuthenticationBlocked(authentication)

                currentConnection == McpClientState.AuthenticationBlocked ->
                    McpServerSettingsStatus.AuthenticationBlocked(authentication)

                currentConnection == null -> McpServerSettingsStatus.Connecting
                currentConnection == McpClientState.Connecting ->
                    McpServerSettingsStatus.Connecting

                currentConnection == McpClientState.Healthy ->
                    McpServerSettingsStatus.Healthy(toolCount)

                currentConnection is McpClientState.Failed ->
                    McpServerSettingsStatus.Failed(currentConnection.reason)

                currentConnection == McpClientState.Closed -> McpServerSettingsStatus.Closed
                else -> McpServerSettingsStatus.Connecting
            }
        },
        headerNames = headerNames,
        environmentNames = environmentNames,
        oauth = oauth,
        streamableHttpUrl = streamableHttpUrl,
        stdioCommand = stdioCommand,
        stdioArguments = stdioArguments,
        stdioWorkingDirectory = stdioWorkingDirectory,
    )

private fun McpAuthenticationState.isBlocked(): Boolean =
    when (this) {
        McpAuthenticationState.NotConfigured,
        McpAuthenticationState.Authorized,
            -> false

        McpAuthenticationState.LoginRequired,
        McpAuthenticationState.ReauthorizationRequired,
        McpAuthenticationState.Authorizing,
        McpAuthenticationState.Refreshing,
        is McpAuthenticationState.Failed,
            -> true
    }

internal fun io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
    .usageResetRequestOrNull(): UsageResetRequest? {
    val detailedOptions = resetCredits.credits.orEmpty().map { credit ->
        UsageResetOption(
            creditId = credit.id,
            title = credit.title ?: "Full reset",
            description = credit.description ?: "Reset your current usage limits.",
            expiresAt = credit.expiresAt,
        )
    }
    val availableCount = resetCredits.availableCount ?: detailedOptions.size.toLong()
    if (availableCount <= 0L && detailedOptions.isEmpty()) return null
    return UsageResetRequest(
        availableCount = availableCount.coerceAtLeast(detailedOptions.size.toLong()),
        options = detailedOptions.ifEmpty {
            listOf(
                UsageResetOption(
                    creditId = null,
                    title = "Full reset",
                    description = "Reset your current usage limits.",
                    expiresAt = null,
                ),
            )
        },
    )
}
