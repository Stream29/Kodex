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
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/** Complete frozen [GlobalRpc], delegating to the same owned backend graph. */
public expect class BackendGlobalRpc(
    host: BackendSessionHost,
    settings: BackendGlobalState,
    account: BackendAccountState,
    oauth: BackendOAuth,
    sessions: BackendSessionManagement,
    notifications: BackendNotifications,
) : GlobalRpc {
    override suspend fun getSettings(): BackendSettings
    override suspend fun getFrontendSettings(): CliFrontendSettings
    override fun getFrontendSettingsFlow(): Flow<CliFrontendSettings>
    override suspend fun compareAndSetFrontendSettings(
        expect: CliFrontendSettings,
        update: CliFrontendSettings,
    ): Boolean
    override fun getSettingsFlow(): Flow<BackendSettings>
    override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean
    override suspend fun getModels(): List<ModelInfo>
    override fun getModelsFlow(): Flow<List<ModelInfo>>
    override fun getNotificationFlow(): Flow<Notification>
    override suspend fun getAuthentication(): SettingsAuthenticationState
    override fun getAuthenticationFlow(): Flow<SettingsAuthenticationState>
    override suspend fun getAccountUsage(): SettingsAccountUsageState
    override fun getAccountUsageFlow(): Flow<SettingsAccountUsageState>
    override suspend fun refreshAccountUsage(): Unit
    override suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome
    override suspend fun startOAuthLogin(target: OAuthTarget, redirectUri: String): OAuthAuthorization
    override suspend fun completeOAuthLogin(attemptId: Long, callbackUrl: String): Unit
    override suspend fun cancelOAuthLogin(attemptId: Long): Unit
    override suspend fun removeAuthentication(source: KodexAuthSource): Unit
    override suspend fun getMcpServers(): List<McpManagedServerState>
    override fun getMcpServersFlow(): Flow<List<McpManagedServerState>>
    override suspend fun reconnectMcpServer(serverName: String): Unit
    override suspend fun logoutMcpServer(serverName: String): Unit
    override suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate>
    override suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry>
    override suspend fun createSession(cwd: Path): Int
    override suspend fun createSuggestedSessions(
        tasks: List<SuggestedSubagentTask>,
        initialSettings: KodexAgentSettings,
    ): List<CreatedSuggestedSession>
    override suspend fun keepSessionAlive(sessionIndex: Int): Unit
    override suspend fun archiveSession(sessionIndex: Int): Unit
    override suspend fun unarchiveSession(sessionIndex: Int): Unit
    override suspend fun forkSession(sessionIndex: Int): Int
    override suspend fun forkSessionHistory(
        sessionIndex: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ): Int
    override suspend fun deleteSession(sessionIndex: Int): Boolean
}
