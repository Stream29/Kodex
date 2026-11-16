@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.GeneratedImageArtifact
import io.github.stream29.kodex.rpc.models.ImageGenToolArguments
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/**
 * RPC runtime adapter over [KodexSessionRepository.openSessions] and [AgentEventBus].
 *
 * Reads and commands require an already-open Session (`openSessions.value[sessionIndex]`), failing with
 * `SessionNotActive` without implicitly opening unloaded entries or acquiring an untouched
 * repository. Accepted commands execute in the owning Session's [kotlinx.coroutines.CoroutineScope]
 * so cancelling an RPC waiter does not cancel accepted work. Stop notifications are emitted by
 * `AgentRuntime.stopEvents` itself, while [createAskUser] immediately publishes
 * [Notification.AskUserCreated] via [onAskUserCreated].
 */
public expect class BackendAgentRuntimeRpc(
    repository: KodexSessionRepository,
    eventBus: AgentEventBus = AgentEventBus(repository),
    client: OpenAiClient? = null,
    home: Path? = null,
    onAskUserCreated: (Notification.AskUserCreated) -> Unit = {},
    checkAdmission: () -> Unit = {},
) : AgentRuntimeRpc {

    override suspend fun getStorageUri(sessionIndex: Int): String
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun getState(sessionIndex: Int): AgentStateValue
    override fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue>
    override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent>
    override suspend fun submit(sessionIndex: Int, content: List<ContentItem>)
    override suspend fun submitAskUserAnswer(
        sessionIndex: Int,
        askId: Int,
        response: RequestUserInputResponse,
    )
    override suspend fun createAskUser(
        sessionIndex: Int,
        arguments: RequestUserInputArgs,
    ): AskUserEntry
    override suspend fun runWebSearch(
        sessionIndex: Int,
        commands: SearchCommands,
    ): SearchResponse
    override suspend fun generateImage(
        sessionIndex: Int,
        arguments: ImageGenToolArguments,
    ): GeneratedImageArtifact
    override suspend fun appendUserMessage(sessionIndex: Int, content: List<ContentItem>): Int
    override suspend fun resume(sessionIndex: Int)
    override suspend fun forcedCompact(sessionIndex: Int): Int
    override suspend fun clearPending(sessionIndex: Int): Int
    override suspend fun completeToolCall(sessionIndex: Int, completed: StableCleanEvent.CompletedTool): Int
    override suspend fun revertHistory(sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long)
    override suspend fun getShellSessions(sessionIndex: Int): Map<Int, ShellSessionState>
    override fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>>
    override suspend fun readShellSessionOutput(
        sessionIndex: Int,
        shellSessionId: Int,
        offset: ULong,
        limit: UInt,
    ): StdoutSlice
    override suspend fun writeShellSessionStdin(
        sessionIndex: Int,
        shellSessionId: Int,
        chars: String,
        closeInput: Boolean,
    )
    override suspend fun closeShellSession(sessionIndex: Int, shellSessionId: Int)
    override suspend fun getRunningTurn(sessionIndex: Int): Boolean
    override fun getRunningTurnFlow(sessionIndex: Int): Flow<Boolean>
    override suspend fun cancelRunningTurn(sessionIndex: Int)
}
