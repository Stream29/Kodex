package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.GlobalSettingsEffect
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsState
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.cli.sessiontitle.DefaultSessionTitleModel
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.hook.contract.HookImportDecision
import io.github.stream29.kodex.hook.contract.HookImportPreview
import io.github.stream29.kodex.hook.contract.HookManagedSourceState
import io.github.stream29.kodex.hook.contract.HookManager
import io.github.stream29.kodex.hook.contract.HookSourceDraft
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
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
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

internal class GlobalSettingsViewModelImpl(
    private val globalSettings: KodexGlobalSettingsStore,
    authentication: OpenAiAuthStore,
    private val accountUsageStore: CodexAccountUsageStore,
    private val mcpManager: McpManager,
    private val hookManager: HookManager,
    models: StateFlow<List<ModelInfo>>,
    private val commandScope: CoroutineScope,
) : GlobalSettingsViewModel {
    private data class PreparedReset(
        val option: UsageResetOption,
        val attempt: CodexRateLimitResetAttempt,
    )

    private val scope = commandScope.supervisorChildScope()
    private val updates = SettingsUpdateQueue(commandScope)
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
    private val mutableAuthentication = MutableStateFlow(authentication.state.value.toSettingsState())
    private val mutableAccountUsage = MutableStateFlow(
        accountUsageStore.state.value.toSettingsState(),
    )
    private val mutableMcpServers = MutableStateFlow<List<McpServerSettingsState>>(emptyList())
    private val mutableMcpImportPreview = MutableStateFlow<McpImportPreview?>(null)
    private val mutableHookImportPreview = MutableStateFlow<HookImportPreview?>(null)
    private val mutableUsageReset = MutableStateFlow<UsageResetState>(UsageResetState.Hidden)

    override val state: StateFlow<GlobalSettingsState> = mutableState.asStateFlow()
    override val authentication: StateFlow<SettingsAuthenticationState> =
        mutableAuthentication.asStateFlow()
    override val accountUsage: StateFlow<SettingsAccountUsageState> =
        mutableAccountUsage.asStateFlow()
    override val mcpServers: StateFlow<List<McpServerSettingsState>> =
        mutableMcpServers.asStateFlow()
    override val mcpImportPreview: StateFlow<McpImportPreview?> =
        mutableMcpImportPreview.asStateFlow()
    override val hooksEnabled: StateFlow<Boolean> = hookManager.featureEnabled
    override val hookSources: StateFlow<List<HookManagedSourceState>> = hookManager.sources
    override val hookImportPreview: StateFlow<HookImportPreview?> =
        mutableHookImportPreview.asStateFlow()
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
            authentication.state.collect { authState ->
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

    override fun updateNewLineKey(newLineKey: NewLineKey) {
        enqueueSettingsUpdate {
            copy(newLineKey = newLineKey)
        }
    }

    override fun updateAuthSource(authSource: KodexAuthSource) {
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

    override fun requestLogin() {
        if (!closed && mutableAuthentication.value is SettingsAuthenticationState.Unavailable) {
            dismissUsageReset()
            effectChannel.trySend(GlobalSettingsEffect.OpenLogin)
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

    override fun setHooksEnabled(enabled: Boolean) {
        launchManagementCommand { hookManager.setFeatureEnabled(enabled) }
    }

    override fun addHookSource(draft: HookSourceDraft) {
        launchManagementCommand { hookManager.add(draft) }
    }

    override fun editHookSource(sourceId: String, draft: HookSourceDraft) {
        launchManagementCommand { hookManager.edit(sourceId, draft) }
    }

    override fun deleteHookSource(sourceId: String) {
        launchManagementCommand { hookManager.delete(sourceId) }
    }

    override fun setHookSourceEnabled(sourceId: String, enabled: Boolean) {
        launchManagementCommand { hookManager.setEnabled(sourceId, enabled) }
    }

    override fun hookSourceEditorDraft(sourceId: String): HookSourceDraft? =
        hookManager.editorDraft(sourceId)

    override fun previewCodexHookImport(filter: String) {
        launchManagementCommand {
            mutableHookImportPreview.value = hookManager.previewCodexImport(filter)
        }
    }

    override fun applyCodexHookImport(
        previewId: Long,
        decisions: Map<String, HookImportDecision>,
    ) {
        launchManagementCommand {
            hookManager.applyCodexImport(previewId, decisions)
            mutableHookImportPreview.value = null
        }
    }

    override fun dismissCodexHookImport() {
        mutableHookImportPreview.value = null
    }

    override fun close() {
        if (closed) return
        closed = true
        dismissUsageReset()
        dismissCodexMcpImport()
        dismissCodexHookImport()
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
            } catch (_: Throwable) {
                // Manager commands validate before any atomic settings update.
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
        codexHome = codexHome,
        authSource = authSource,
        newLineKey = newLineKey,
        sessionTitle = sessionTitle,
        effectiveSessionTitleModel = effectiveTitleModel,
        modelOptions = modelOptions,
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
