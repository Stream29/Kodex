package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.settings.effectiveModel
import io.github.stream29.kodex.app.settings.staticContextSourcePaths
import io.github.stream29.kodex.app.settings.toContextPath
import io.github.stream29.kodex.app.settings.withBuiltIn
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginDependencies
import io.github.stream29.kodex.app.settings.SettingsUpdateQueue
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel
import io.github.stream29.kodex.app.hooksettings.createHookSettingsViewModel
import io.github.stream29.kodex.app.mcpsettings.McpSettingsViewModel
import io.github.stream29.kodex.app.mcpsettings.createMcpSettingsViewModel
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Page observations and transient commands are disposable; accepted writes drain in the app scope. */
public class RpcGlobalEditor(
    public val global: RpcGlobalSettings,
    public val rpc: GlobalRpc,
    applicationScope: CoroutineScope,
) : GlobalSettingsViewModel {
    private val owner = Job(applicationScope.coroutineContext[Job])
    public val scope: CoroutineScope = CoroutineScope(applicationScope.coroutineContext + owner)
    // Durable edits outlive the popup; preview, authentication UI and observations do not.
    private val updates = SettingsUpdateQueue(applicationScope, defaultReportError = global::reportOperationFailure)
    public val mcp: RpcMcpSettings = RpcMcpSettings(global, rpc, applicationScope)
    public val reset: RpcUsageReset = RpcUsageReset(rpc, global.usage, this.scope)
    override val operationFailure: StateFlow<Boolean> = global.operationFailure
    override fun dismissOperationFailure() { global.dismissOperationFailure() }
    private val mutable = MutableStateFlow(project(0))
    override val state: StateFlow<GlobalSettingsState> = mutable.asStateFlow()
    private val authOperation = MutableStateFlow<SettingsAuthenticationOperationState>(SettingsAuthenticationOperationState.Idle)
    override val authenticationOperation: StateFlow<SettingsAuthenticationOperationState> = authOperation.asStateFlow()
    private val effectsChannel = Channel<GlobalSettingsEffect>(Channel.BUFFERED)
    override val effects: Flow<GlobalSettingsEffect> = effectsChannel.receiveAsFlow()
    override val authentication: StateFlow<SettingsAuthenticationState> = global.authentication
    override val accountUsage: StateFlow<SettingsAccountUsageState> = global.usage
    override val mcpSettings: McpSettingsViewModel = createMcpSettingsViewModel(
        RpcMcpSettingsDependencies(global, rpc, mcp, ::acceptWrite),
        this.scope,
    )
    private val hookSource: StateFlow<List<NotificationHook>> = global.frontend.settings.map { it.hooks }
        .stateIn(this.scope, SharingStarted.Eagerly, global.frontend.settings.value.hooks)
    override val hookSettings: HookSettingsViewModel = createHookSettingsViewModel(
        RpcHookSettingsDependencies(global, hookSource, ::acceptWrite),
        this.scope,
    )
    override val usageReset: StateFlow<UsageResetState> = reset.state

    init {
        this.scope.launch {
            combine(global.settings, global.frontend.settings, global.sidebarWidths, global.models) { _, _, _, _ -> Unit }
                .collect {
                    val previous = mutable.value
                    val next = project(previous.settingsRevision)
                    if (next != previous) mutable.value = next.copy(settingsRevision = previous.settingsRevision + 1)
                }
        }
    }

    private fun project(revision: Long): GlobalSettingsState {
        val backend = global.settings.value
        val frontend = global.frontend.settings.value
        val widths = global.sidebarWidths.value
        val titleModel = backend.sessionTitle.effectiveModel()
        return GlobalSettingsState(
            revision, backend.authSource, frontend.newLineKey, backend.contextSources, backend.sessionTitle,
            SidebarSettings(frontend.sidebars.left, frontend.sidebars.right,
                widths.first.coerceAtLeast(MinimumSidebarWidthColumns),
                widths.second.coerceAtLeast(MinimumSidebarWidthColumns)),
            titleModel, (global.models.value.map { it.slug } + titleModel).distinct(),
        )
    }

    public fun command(action: suspend () -> Unit) {
        if (!owner.isActive) return
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { global.reportOperationFailure(error) }
        }
    }

    private fun persist(action: suspend () -> Unit) {
        acceptWrite(action)
    }

    /** Admission only: all settings children share this queue and its original ordering. */
    private fun acceptWrite(action: suspend () -> Unit): Boolean {
        if (!owner.isActive) return false
        updates.submit {
            action()
            global.dismissOperationFailure()
        }
        return true
    }

    private fun <F> edit(select: (BackendSettings) -> F, update: (BackendSettings) -> BackendSettings) {
        if (!owner.isActive) return
        val initial = select(global.settings.value)
        persist {
            global.settings.editField(initial, select, update, global::ensureActive)
        }
    }

    override fun updateAuthSource(authSource: KodexAuthSource) {
        reset.dismiss()
        edit({ it.authSource }, { it.copy(authSource = authSource) })
    }
    override fun updateSessionTitleEnabled(enabled: Boolean): Unit =
        edit({ it.sessionTitle.enabled }, { it.copy(sessionTitle = it.sessionTitle.copy(enabled = enabled)) })
    override fun updateSessionTitleModel(model: OpenAiModelId): Unit =
        edit({ it.sessionTitle.model }, { it.copy(sessionTitle = it.sessionTitle.copy(model = model)) })
    override fun updateSessionTitleReasoningEffort(reasoningEffort: ReasoningEffort): Unit =
        edit({ it.sessionTitle.reasoningEffort }, { it.copy(sessionTitle = it.sessionTitle.copy(reasoningEffort = reasoningEffort)) })
    override fun updateNewLineKey(newLineKey: NewLineKey): Unit = persist {
        global.frontend.update { it.copy(newLineKey = newLineKey) }
    }
    override fun updateLeftSidebarWidth(columns: Int) {
        if (owner.isActive) global.resizeSidebars(columns, global.sidebarWidths.value.second)
    }
    override fun updateRightSidebarWidth(columns: Int) {
        if (owner.isActive) global.resizeSidebars(global.sidebarWidths.value.first, columns)
    }
    override fun setBuiltInContextSourceEnabled(source: BuiltInContextSource, enabled: Boolean): Unit =
        edit({ it.contextSources.enabled(source) }, { it.copy(contextSources = it.contextSources.withBuiltIn(source, enabled)) })

    override fun addCustomContextSource(path: String): String? {
        val input = path.trim()
        val normalized = input.toContextPath()?.toString() ?: return "Enter an absolute path, ~, or ~/path."
        if (normalized in staticContextSourcePaths()) return "This path is already a built-in context source."
        edit({ it.contextSources.customSources }, { value ->
            val existing = value.contextSources.customSources
            val duplicate = existing.indexOfFirst { it.path.toContextPath()?.toString() == normalized }
            val updated = if (duplicate < 0) existing + AgentContextCustomSource(input)
            else existing.mapIndexed { index, item -> if (index == duplicate) item.copy(enabled = true) else item }
            value.copy(contextSources = value.contextSources.copy(customSources = updated))
        })
        return null
    }
    override fun setCustomContextSourceEnabled(path: String, enabled: Boolean): Unit =
        edit({ it.contextSources.customSources.find { item -> item.path == path } }, { value ->
            value.copy(contextSources = value.contextSources.copy(customSources =
                value.contextSources.customSources.map { if (it.path == path) it.copy(enabled = enabled) else it },
            ))
        })
    override fun removeCustomContextSource(path: String): Unit =
        edit({ it.contextSources.customSources.find { item -> item.path == path } }, { value ->
            value.copy(contextSources = value.contextSources.copy(customSources =
                value.contextSources.customSources.filterNot { it.path == path },
            ))
        })

    override fun removeAuthentication() {
        if (!owner.isActive || authOperation.value == SettingsAuthenticationOperationState.SigningOut) return
        val source = global.settings.value.authSource
        authOperation.value = SettingsAuthenticationOperationState.SigningOut
        command {
            try {
                rpc.removeAuthentication(source)
                authOperation.value = SettingsAuthenticationOperationState.Idle
            } catch (error: CancellationException) { throw error }
            catch (_: Throwable) {
                authOperation.value = SettingsAuthenticationOperationState.Failed(SettingsAuthenticationOperation.Logout)
            }
        }
    }
    override fun dismissAuthenticationOperationFailure() { authOperation.value = SettingsAuthenticationOperationState.Idle }
    override fun refreshUsage(): Unit = command { rpc.refreshAccountUsage() }
    override fun requestLogin(): Unit = command { effectsChannel.send(GlobalSettingsEffect.OpenLogin) }
    override fun requestUsageReset(): Unit = reset.show()
    override fun selectUsageReset(option: UsageResetOption) { option.creditId?.let(reset::select) }
    override fun returnToUsageResetChoices(): Unit = reset.show()
    override fun confirmUsageReset() { (reset.state.value as? UsageResetState.Confirming)?.let(reset::confirm) }
    override fun retryUsageReset(): Unit = reset.show()
    override fun dismissUsageReset(): Unit = reset.dismiss()
    public fun createLogin(ownerScope: CoroutineScope = scope): OpenAiLoginViewModel {
        owner.ensureActive()
        val target = OAuthTarget.OpenAi(global.settings.value.authSource)
        return createOpenAiLoginViewModel(
            dependencies = OpenAiLoginDependencies { startRpcOAuth(rpc, target, ownerScope) },
            ownerScope = ownerScope,
        )
    }

    override fun close() {
        mcpSettings.close()
        hookSettings.close()
        owner.cancel(); effectsChannel.close(); reset.close()
        updates.close(mcp::close)
    }
}

private fun AgentContextSourceSettings.enabled(source: BuiltInContextSource): Boolean = when (source) {
    BuiltInContextSource.AgentsHome -> agentsHomeEnabled
    BuiltInContextSource.KodexHome -> kodexHomeEnabled
    BuiltInContextSource.CodexHome -> codexHomeEnabled
    BuiltInContextSource.GitRoot -> gitRootEnabled
    BuiltInContextSource.WorkingDirectory -> workingDirectoryEnabled
}
