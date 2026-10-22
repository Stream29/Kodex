@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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
public actual class BackendGlobalRpc actual constructor(
    private val host: BackendSessionHost,
    private val settings: BackendGlobalState,
    private val account: BackendAccountState,
    private val oauth: BackendOAuth,
    private val sessions: BackendSessionManagement,
    private val notifications: BackendNotifications,
) : GlobalRpc {
    private inline fun <T> observation(read: () -> T): T { host.ensureAdmission(); return read() }
    actual override suspend fun getSettings(): BackendSettings = observation { settings.settings.value }
    actual override suspend fun getFrontendSettings(): CliFrontendSettings = observation { settings.unified.current.frontend }
    actual override fun getFrontendSettingsFlow(): Flow<CliFrontendSettings> = observation { settings.unified.frontendSettings }
    actual override suspend fun compareAndSetFrontendSettings(expect: CliFrontendSettings, update: CliFrontendSettings): Boolean =
        host.inBackend { settings.unified.compareAndSetFrontend(expect, update) }
    actual override fun getSettingsFlow(): Flow<BackendSettings> = observation { settings.settings }
    actual override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean =
        host.inBackend { settings.compareAndSetSettings(expect, update) }
    actual override suspend fun getModels(): List<ModelInfo> = observation { settings.models.value }
    actual override fun getModelsFlow(): Flow<List<ModelInfo>> = observation { settings.models }
    actual override fun getNotificationFlow(): Flow<Notification> = observation { notifications.flow }
    actual override suspend fun getAuthentication(): SettingsAuthenticationState = observation { account.authentication.value }
    actual override fun getAuthenticationFlow(): Flow<SettingsAuthenticationState> = observation { account.authentication }
    actual override suspend fun getAccountUsage(): SettingsAccountUsageState = observation { account.accountUsage.value }
    actual override fun getAccountUsageFlow(): Flow<SettingsAccountUsageState> = observation { account.accountUsage }
    actual override suspend fun refreshAccountUsage(): Unit = host.inBackend { account.refreshAccountUsage() }
    actual override suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome =
        host.inBackend { account.consumeUsageReset(creditId) }
    actual override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String): OAuthAuthorization =
        host.inBackend { oauth.start(target, redirectUri) }
    actual override suspend fun completeOAuthLogin(attemptId: Long, callbackUrl: String): Unit =
        host.inBackend { oauth.complete(attemptId, callbackUrl) }
    actual override suspend fun cancelOAuthLogin(attemptId: Long): Unit = host.inBackend { oauth.cancel(attemptId) }
    actual override suspend fun removeAuthentication(source: KodexAuthSource): Unit = host.inBackend { oauth.removeAuthentication(source) }
    actual override suspend fun getMcpServers(): List<McpManagedServerState> = observation { settings.mcpServers.value }
    actual override fun getMcpServersFlow(): Flow<List<McpManagedServerState>> = observation { settings.mcpServers }
    actual override suspend fun reconnectMcpServer(serverName: String): Unit =
        host.inBackend { settings.reconnectMcpServer(serverName) }
    actual override suspend fun logoutMcpServer(serverName: String): Unit = host.inBackend { oauth.logoutMcpServer(serverName) }
    actual override suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate> =
        host.inBackend { settings.getCodexMcpSettings() }
    actual override suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry> =
        sessions.getSessionCatalog(includeArchived)
    actual override suspend fun createSession(cwd: Path): Int = sessions.createSession(cwd)
    actual override suspend fun createSuggestedSessions(
        tasks: List<SuggestedSubagentTask>, initialSettings: KodexAgentSettings,
    ): List<CreatedSuggestedSession> = sessions.createSuggestedSessions(tasks, initialSettings)
    actual override suspend fun keepSessionAlive(sessionIndex: Int): Unit = sessions.keepSessionAlive(sessionIndex)
    actual override suspend fun archiveSession(sessionIndex: Int): Unit = sessions.archiveSession(sessionIndex)
    actual override suspend fun unarchiveSession(sessionIndex: Int): Unit = sessions.unarchiveSession(sessionIndex)
    actual override suspend fun forkSession(sessionIndex: Int): Int = sessions.forkSession(sessionIndex)
    actual override suspend fun forkSessionHistory(sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long): Int =
        sessions.forkSessionHistory(sessionIndex, untilExclusive, expectedCacheNonce)
    actual override suspend fun deleteSession(sessionIndex: Int): Boolean = sessions.deleteSession(sessionIndex)
}
