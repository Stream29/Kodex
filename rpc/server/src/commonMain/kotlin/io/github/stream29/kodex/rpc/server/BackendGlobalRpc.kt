package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/** Complete frozen GlobalRpc, delegating to the same owned backend graph. */
public class BackendGlobalRpc(
    private val host: BackendSessionHost,
    private val settings: BackendGlobalState,
    private val account: BackendAccountState,
    private val oauth: BackendOAuth,
    private val sessions: BackendSessionManagement,
    private val notifications: BackendNotifications,
) : GlobalRpc {
    private inline fun <T> observation(read: () -> T): T { host.ensureAdmission(); return read() }
    override suspend fun getSettings(): BackendSettings = observation { settings.settings.value }
    override suspend fun getFrontendSettings(): CliFrontendSettings = observation { settings.unified.current.frontend }
    override fun getFrontendSettingsFlow(): Flow<CliFrontendSettings> = observation { settings.unified.frontendSettings }
    override suspend fun compareAndSetFrontendSettings(expect: CliFrontendSettings, update: CliFrontendSettings): Boolean =
        host.inBackend { settings.unified.compareAndSetFrontend(expect, update) }
    override fun getSettingsFlow(): Flow<BackendSettings> = observation { settings.settings }
    override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean =
        host.inBackend { settings.compareAndSetSettings(expect, update) }
    override suspend fun getModels(): List<ModelInfo> = observation { settings.models.value }
    override fun getModelsFlow(): Flow<List<ModelInfo>> = observation { settings.models }
    override fun getNotificationFlow(): Flow<Notification> = observation { notifications.flow }
    override suspend fun getAuthentication(): SettingsAuthenticationState = observation { account.authentication.value }
    override fun getAuthenticationFlow(): Flow<SettingsAuthenticationState> = observation { account.authentication }
    override suspend fun getAccountUsage(): SettingsAccountUsageState = observation { account.accountUsage.value }
    override fun getAccountUsageFlow(): Flow<SettingsAccountUsageState> = observation { account.accountUsage }
    override suspend fun refreshAccountUsage(): Unit = host.inBackend { account.refreshAccountUsage() }
    override suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome =
        host.inBackend { account.consumeUsageReset(creditId) }
    override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String): OAuthAuthorization =
        host.inBackend { oauth.start(target, redirectUri) }
    override suspend fun completeOAuthLogin(attemptId: Long, callbackUrl: String): Unit =
        host.inBackend { oauth.complete(attemptId, callbackUrl) }
    override suspend fun cancelOAuthLogin(attemptId: Long): Unit = host.inBackend { oauth.cancel(attemptId) }
    override suspend fun removeAuthentication(source: KodexAuthSource): Unit = host.inBackend { oauth.removeAuthentication(source) }
    override suspend fun getMcpServers(): List<McpManagedServerState> = observation { settings.mcpServers.value }
    override fun getMcpServersFlow(): Flow<List<McpManagedServerState>> = observation { settings.mcpServers }
    override suspend fun reconnectMcpServer(serverName: String): Unit =
        host.inBackend { settings.reconnectMcpServer(serverName) }
    override suspend fun logoutMcpServer(serverName: String): Unit = host.inBackend { oauth.logoutMcpServer(serverName) }
    override suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate> =
        host.inBackend { settings.getCodexMcpSettings() }
    override suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry> =
        sessions.getSessionCatalog(includeArchived)
    override suspend fun createSession(cwd: Path): Int = sessions.createSession(cwd)
    override suspend fun createSuggestedSessions(
        tasks: List<SuggestedSubagentTask>, initialSettings: KodexAgentSettings,
    ): List<CreatedSuggestedSession> = sessions.createSuggestedSessions(tasks, initialSettings)
    override suspend fun keepSessionAlive(sessionIndex: Int): Unit = sessions.keepSessionAlive(sessionIndex)
    override suspend fun archiveSession(sessionIndex: Int): Unit = sessions.archiveSession(sessionIndex)
    override suspend fun unarchiveSession(sessionIndex: Int): Unit = sessions.unarchiveSession(sessionIndex)
    override suspend fun forkSession(sessionIndex: Int): Int = sessions.forkSession(sessionIndex)
    override suspend fun forkSessionHistory(sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long): Int =
        sessions.forkSessionHistory(sessionIndex, untilExclusive, expectedCacheNonce)
    override suspend fun deleteSession(sessionIndex: Int): Boolean = sessions.deleteSession(sessionIndex)
}
