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
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal class GlobalSettingsViewModelImpl(
    private val globalSettings: KodexGlobalSettingsStore,
    authentication: OpenAiAuthStore,
    private val accountUsageStore: CodexAccountUsageStore,
    private val mcpService: McpService,
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
    private val mutableUsageReset = MutableStateFlow<UsageResetState>(UsageResetState.Hidden)

    override val state: StateFlow<GlobalSettingsState> = mutableState.asStateFlow()
    override val authentication: StateFlow<SettingsAuthenticationState> =
        mutableAuthentication.asStateFlow()
    override val accountUsage: StateFlow<SettingsAccountUsageState> =
        mutableAccountUsage.asStateFlow()
    override val mcpServers: StateFlow<List<McpServerSettingsState>> =
        mutableMcpServers.asStateFlow()
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
            combine(globalSettings.settings, mcpService.clients) { settings, clients ->
                settings.mcpServers to clients
            }.collectLatest { (configurations, clients) ->
                publishMcpServers(configurations, clients)
                coroutineScope {
                    clients.values.forEach { client ->
                        launch {
                            client.state.collect {
                                publishMcpServers(configurations, clients)
                            }
                        }
                    }
                    awaitCancellation()
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
        if (closed) return
        val server = mutableMcpServers.value
            .firstOrNull { candidate -> candidate.serverName == serverName }
            ?: return
        if (server.status !is McpServerSettingsStatus.Failed) return
        val client = mcpService.clients.value[serverName] ?: return
        if (client.state.value !is McpClientState.Failed) return
        commandScope.launch {
            try {
                client.reconnect()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The managed client publishes its typed failure state.
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        dismissUsageReset()
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

    private fun publishMcpServers(
        configurations: Map<String, McpServerConfiguration>,
        clients: Map<String, McpClient>,
    ) {
        mutableMcpServers.value = configurations.entries
            .sortedBy(Map.Entry<String, McpServerConfiguration>::key)
            .map { (serverName, configuration) ->
                McpServerSettingsState(
                    serverName = serverName,
                    status = when {
                        !configuration.enabled -> McpServerSettingsStatus.Disabled
                        clients[serverName] == null -> McpServerSettingsStatus.Connecting
                        else -> clients.getValue(serverName).toSettingsStatus()
                    },
                )
            }
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

private fun McpClient.toSettingsStatus(): McpServerSettingsStatus =
    when (val current = state.value) {
        McpClientState.Connecting -> McpServerSettingsStatus.Connecting
        McpClientState.Healthy -> McpServerSettingsStatus.Healthy(
            toolCount = runCatching { listTools().size }.getOrDefault(0),
        )

        is McpClientState.Failed -> McpServerSettingsStatus.Failed(current.reason)
        McpClientState.Closed -> McpServerSettingsStatus.Closed
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
