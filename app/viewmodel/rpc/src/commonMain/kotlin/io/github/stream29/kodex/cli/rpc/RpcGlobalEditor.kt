package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.settings.effectiveModel
import io.github.stream29.kodex.app.settings.staticContextSourcePaths
import io.github.stream29.kodex.app.settings.toContextPath
import io.github.stream29.kodex.app.settings.withBuiltIn
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.SettingsUpdateQueue
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
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
    override val mcpServers: StateFlow<List<McpServerSettingsState>> = mcp.servers
    private val importPreview = MutableStateFlow<McpImportPreview?>(null)
    override val mcpImportPreview: StateFlow<McpImportPreview?> = importPreview.asStateFlow()
    override val hooks: StateFlow<List<NotificationHook>> = global.frontend.settings.map { it.hooks }
        .stateIn(this.scope, SharingStarted.Eagerly, global.frontend.settings.value.hooks)
    override val usageReset: StateFlow<UsageResetState> = reset.state
    private val mcpEdits = mutableMapOf<String, Map<String, McpServerConfiguration>>()
    private val hookEdits = mutableMapOf<String, NotificationHook>()
    private val loginJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

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
        if (!owner.isActive) return
        updates.submit {
            action()
            global.dismissOperationFailure()
        }
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

    public fun saveHook(original: NotificationHook?, updated: NotificationHook): Unit = persist {
        global.frontend.update { value ->
            val current = value.hooks.find { it.name == (original?.name ?: updated.name) }
            if (current != original) value
            else value.copy(hooks = if (original == null) value.hooks + updated
            else value.hooks.map { if (it.name == original.name) updated else it })
        }
    }
    override fun deleteHook(hook: NotificationHook): Unit = persist {
        global.frontend.update { value ->
            if (value.hooks.find { it.name == hook.name } != hook) value
            else value.copy(hooks = value.hooks.filterNot { it.name == hook.name })
        }
    }

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
    override fun addHook(draft: NotificationHook): Unit = saveHook(null, draft)
    override fun editHook(name: String, draft: NotificationHook) {
        hookEdits.remove(name)?.let { saveHook(it, draft) }
    }
    override fun hookEditorDraft(name: String): NotificationHook? =
        global.frontend.settings.value.hooks.find { it.name == name }?.also { hookEdits[name] = it }

    override fun reconnectMcpServer(serverName: String): Unit = command { mcp.reconnect(serverName) }
    override fun logoutMcpServer(serverName: String): Unit = command { mcp.logout(serverName) }
    override fun mcpEditorDraft(serverName: String): McpServerDraft? {
        val captured = mcp.capture()
        val value = captured[serverName] ?: return null
        mcpEdits[serverName] = captured
        return value.editorDraft(serverName)
    }
    override fun addMcpServer(draft: McpServerDraft): Unit = persist {
        check(mcp.save(emptyMap(), null, draft)) { "The selected MCP name is no longer available." }
    }
    override fun editMcpServer(existingServerName: String, draft: McpServerDraft) {
        val captured = mcpEdits.remove(existingServerName) ?: return
        persist { check(mcp.save(captured, existingServerName, draft)) { "The MCP configuration changed." } }
    }
    override fun deleteMcpServer(serverName: String) {
        val captured = mcp.capture()[serverName] ?: return
        persist { check(mcp.delete(serverName, captured)) { "The MCP configuration changed." } }
    }
    override fun setMcpServerEnabled(serverName: String, enabled: Boolean) {
        val captured = mcp.capture()[serverName] ?: return
        persist { check(mcp.setEnabled(serverName, captured, enabled)) { "The MCP configuration changed." } }
    }
    override fun previewCodexMcpImport(filter: String): Unit = command {
        val current = importPreview.value
        importPreview.value = if (current == null) mcp.readImport().let { mcp.filterImport(it.id, filter) }
        else mcp.filterImport(current.id, filter)
    }
    override fun applyCodexMcpImport(previewId: Long, decisions: Map<String, McpImportDecision>): Unit = persist {
        check(mcp.applyImport(previewId, decisions)) { "The import target changed. Open a new preview." }
        importPreview.value = null
    }
    override fun dismissCodexMcpImport() { importPreview.value = null; mcp.dismissImport() }

    public fun createLogin(ownerScope: CoroutineScope = scope): OpenAiLoginViewModel {
        owner.ensureActive()
        val target = OAuthTarget.OpenAi(global.settings.value.authSource)
        return createOpenAiLoginViewModel(ownerScope) { startRpcOAuth(rpc, target, ownerScope) }
    }

    override fun loginMcpServer(serverName: String) {
        val name = serverName
        if (!owner.isActive) return
        val configuration = global.settings.value.mcpServers[name] as? McpServerConfiguration.StreamableHttp ?: return
        val redirect = configuration.oauth?.client?.redirectUri ?: return
        val work = scope.launch(start = CoroutineStart.LAZY) {
            var attempt: KodexAuthLoginAttempt? = null
            try {
                attempt = startRpcOAuth(rpc, OAuthTarget.Mcp(name), scope, redirect)
                effectsChannel.send(GlobalSettingsEffect.OpenMcpAuthorizationUrl(name, attempt.authorizationUrl))
                attempt.awaitCompletion()
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { global.reportOperationFailure(error) }
            finally { attempt?.cancel() }
        }
        while (true) {
            val previous = loginJobs.value
            if (previous[name]?.isActive == true || previous[name]?.isCompleted == false) {
                work.cancel()
                return
            }
            if (loginJobs.compareAndSet(previous, previous + (name to work))) break
        }
        work.invokeOnCompletion {
            loginJobs.update { if (it[name] === work) it - name else it }
        }
        work.start()
    }

    override fun cancelMcpServerLogin(serverName: String) { loginJobs.value[serverName]?.cancel() }
    override fun close() {
        owner.cancel(); effectsChannel.close(); reset.close()
        updates.close(mcp::close)
        mcpEdits.clear(); hookEdits.clear()
    }
}

private fun McpServerConfiguration.editorDraft(name: String): McpServerDraft = when (this) {
    is McpServerConfiguration.StreamableHttp -> McpServerDraft.StreamableHttp(
        name, enabled, McpStreamableHttpDraft(url, headers.mapValues { McpSecretDraft.Keep }, oauth?.let {
            McpOAuthDraft(it.client.clientId, it.client.clientSecret?.let { McpSecretDraft.Keep },
                it.client.redirectUri, it.client.authorizationEndpoint, it.client.tokenEndpoint, it.resource, it.scopes)
        }),
    )
    is McpServerConfiguration.Stdio -> McpServerDraft.Stdio(
        name, enabled, McpStdioDraft(command, args, environment.mapValues { McpSecretDraft.Keep }, workingDirectory),
    )
}

private fun AgentContextSourceSettings.enabled(source: BuiltInContextSource): Boolean = when (source) {
    BuiltInContextSource.AgentsHome -> agentsHomeEnabled
    BuiltInContextSource.KodexHome -> kodexHomeEnabled
    BuiltInContextSource.CodexHome -> codexHomeEnabled
    BuiltInContextSource.GitRoot -> gitRootEnabled
    BuiltInContextSource.WorkingDirectory -> workingDirectoryEnabled
}
