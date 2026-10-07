package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.accountusage.*
import io.github.stream29.kodex.app.applicationpreferences.*
import io.github.stream29.kodex.app.authenticationsettings.*
import io.github.stream29.kodex.app.contextsourcesettings.*
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.sessiontitlesettings.*
import io.github.stream29.kodex.app.settings.SettingsUpdateQueue
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.usagereset.createUsageResetViewModel
import io.github.stream29.kodex.app.usagereset.contract.*
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Stable child composition and host ports; accepted writes use the original application queue. */
public class RpcGlobalEditor(
    public val global: RpcGlobalSettings,
    public val rpc: GlobalRpc,
    applicationScope: CoroutineScope,
) : GlobalSettingsViewModel {
    private val owner = Job(applicationScope.coroutineContext[Job])
    public val scope: CoroutineScope = CoroutineScope(applicationScope.coroutineContext + owner)
    private val updates = SettingsUpdateQueue(applicationScope, defaultReportError = global::reportOperationFailure)
    private val writes = RpcConfigurationWrites(global, ::acceptWrite)
    public val mcp: RpcMcpSettings = RpcMcpSettings(global, rpc, applicationScope)
    private val effectsChannel = Channel<GlobalSettingsEffect>(Channel.BUFFERED)
    private var closed = false
    override val effects: Flow<GlobalSettingsEffect> = effectsChannel.receiveAsFlow()
    override val operationFailure: StateFlow<Boolean> = global.operationFailure
    override fun dismissOperationFailure() { global.dismissOperationFailure() }

    override val contextSourceSettings: ContextSourceSettingsViewModel = createContextSourceSettingsViewModel(
        RpcContextSourceSettingsDependencies(global, global.settings.projectState { it.contextSources }, writes),
        scope,
    )
    override val sessionTitleSettings: SessionTitleSettingsViewModel = createSessionTitleSettingsViewModel(
        RpcSessionTitleSettingsDependencies(global, global.settings.projectState { it.sessionTitle }, writes),
        scope,
    )
    override val applicationPreferences: ApplicationPreferencesViewModel = createApplicationPreferencesViewModel(
        RpcApplicationPreferencesDependencies(global, global.frontend.settings.projectState { it.newLineKey }, ::acceptWrite),
        scope,
    )
    override val usageReset: UsageResetViewModel = createUsageResetViewModel(
        object : UsageResetDependencies {
            override val usage = global.usage
            override suspend fun consume(creditId: String): CodexRateLimitResetOutcome = rpc.consumeUsageReset(creditId)
            override suspend fun refreshUsage() { rpc.refreshAccountUsage() }
        }, scope,
    )
    override val authenticationSettings: AuthenticationSettingsViewModel = createAuthenticationSettingsViewModel(
        object : AuthenticationSettingsDependencies {
            override val selectedSource = global.settings.projectState { it.authSource }
            override val authentication = global.authentication
            override val operationFailure = global.operationFailure
            override fun updateSource(source: KodexAuthSource): Boolean {
                usageReset.dismiss()
                return writes.edit(global.settings.value.authSource, { it.authSource }) {
                    it.copy(authSource = source)
                }
            }
            override suspend fun remove(source: KodexAuthSource) { rpc.removeAuthentication(source) }
            override fun openLogin() {
                if (!owner.isActive) return
                // Preserve the old buffered intent. The host captures target when it creates Login.
                scope.launch { effectsChannel.send(GlobalSettingsEffect.OpenLogin) }
            }
            override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
            override fun dismissFailure() { global.dismissOperationFailure() }
        }, scope,
    )
    override val accountUsage: AccountUsageViewModel = createAccountUsageViewModel(
        object : AccountUsageDependencies {
            override val usage = global.usage
            override val operationFailure = global.operationFailure
            override suspend fun refresh() { rpc.refreshAccountUsage() }
            override fun requestReset() { usageReset.show() }
            override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
            override fun dismissFailure() { global.dismissOperationFailure() }
        }, scope,
    )
    override val mcpSettings: McpSettingsViewModel = createMcpSettingsViewModel(
        RpcMcpSettingsDependencies(global, rpc, mcp, ::acceptWrite), scope,
    )
    override val hookSettings: HookSettingsViewModel = createHookSettingsViewModel(
        RpcHookSettingsDependencies(global, global.frontend.settings.projectState { it.hooks }, ::acceptWrite), scope,
    )

    /** Admission only. Frozen closures drain after child close and preserve original queue order. */
    private fun acceptWrite(action: suspend () -> Unit): Boolean {
        if (!owner.isActive) return false
        return updates.submit { action(); global.dismissOperationFailure() }
    }

    public fun createLogin(ownerScope: CoroutineScope = scope): OpenAiLoginViewModel {
        owner.ensureActive()
        val target = OAuthTarget.OpenAi(global.settings.value.authSource)
        return createOpenAiLoginViewModel(
            dependencies = OpenAiLoginDependencies { startRpcOAuth(rpc, target, ownerScope) },
            ownerScope = ownerScope,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        contextSourceSettings.close()
        sessionTitleSettings.close()
        applicationPreferences.close()
        authenticationSettings.close()
        accountUsage.close()
        usageReset.close()
        mcpSettings.close()
        hookSettings.close()
        owner.cancel()
        effectsChannel.close()
        updates.close { mcp.close() }
    }
}
