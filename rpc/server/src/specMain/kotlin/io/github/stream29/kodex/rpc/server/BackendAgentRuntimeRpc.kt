@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import kotlinx.coroutines.flow.Flow

/** Backend-owned operations and projections; no frontend Job owns an accepted command. */
public expect class BackendAgentRuntimeRpc(
    host: BackendSessionHost,
    titleSettings: () -> SessionTitleSettings,
    titleGenerator: SessionTitleGenerator,
    notifications: BackendNotifications = BackendNotifications(),
) : AgentRuntimeRpc {
    public val notifications: BackendNotifications
    override suspend fun getStorageUri(sessionIndex: Int): String
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun getState(sessionIndex: Int): AgentStateValue
    override fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue>
    override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent>
    override suspend fun appendUserMessage(sessionIndex: Int, content: List<ContentItem>): Int
    override suspend fun resume(sessionIndex: Int)
    override suspend fun forcedCompact(sessionIndex: Int): Int
    override suspend fun clearPending(sessionIndex: Int): Int
    override suspend fun completeToolCall(sessionIndex: Int, completed: StableCleanEvent.CompletedTool): Int
    override suspend fun revertHistory(sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long)
    override suspend fun getShellSessions(sessionIndex: Int): Map<Int, ShellSessionState>
    override fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>>
    override suspend fun closeShellSession(sessionIndex: Int, shellSessionId: Int)
    override suspend fun getRunningTurn(sessionIndex: Int): Boolean
    override fun getRunningTurnFlow(sessionIndex: Int): Flow<Boolean>
    override suspend fun cancelRunningTurn(sessionIndex: Int)
    override suspend fun getPendingSteer(sessionIndex: Int): List<StableIndexEvent.Steerable>
    override fun getPendingSteerFlow(sessionIndex: Int): Flow<List<StableIndexEvent.Steerable>>
    override suspend fun compareAndSetPendingSteer(
        sessionIndex: Int,
        expect: List<StableIndexEvent.Steerable>,
        update: List<StableIndexEvent.Steerable>,
    ): Boolean
}
