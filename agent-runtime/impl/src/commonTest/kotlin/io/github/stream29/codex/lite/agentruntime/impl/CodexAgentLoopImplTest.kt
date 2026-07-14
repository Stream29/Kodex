package io.github.stream29.codex.lite.agentruntime.impl

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.TokenUsage
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CodexAgentLoopImplTest {
    @Test
    fun runtimeExposesOnlyTheReadOnlyAgentStateProperties() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val state = CodexAgentState(
            client = mockOpenAiClient {},
            storage = storage,
        )
        val runtime: CodexAgentRuntime = CodexAgentLoopImpl(state)

        assertEquals(state.state, runtime.state)
        assertEquals(state.latestIndex, runtime.latestIndex)
        assertEquals(state.storage, runtime.storage)
    }

    @Test
    fun loopDoesNotWaitForSlowStreamConsumer() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val productionCompleted = CompletableDeferred<Unit>()
        val firstEventCollected = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val delta = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "x",
        )
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        repeat(1_024) { emit(delta) }
                        emit(ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)))
                        productionCompleted.complete(Unit)
                    }
                }
            },
            storage = storage,
        )
        val runtime = CodexAgentLoopImpl(state)

        state.appendUserMessage(userMessage("Start."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.resume().collect {
                firstEventCollected.complete(Unit)
                releaseConsumer.await()
            }
        }

        firstEventCollected.await()
        yield()
        assertEquals(true, productionCompleted.isCompleted)

        releaseConsumer.complete(Unit)
        runningResume.await()
    }

    @Test
    fun loopContinuesSamplingWhenEndTurnIsFalse() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    when (requests.size) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = assistantMessage("Preparing the answer."),
                            ),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_1",
                                    usage = TokenUsage(10, 2, 12),
                                    endTurn = false,
                                ),
                            ),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = assistantMessage("Done."),
                            ),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_2",
                                    usage = TokenUsage(12, 1, 13),
                                    endTurn = true,
                                ),
                            ),
                        )

                        else -> error("Unexpected request count ${requests.size}.")
                    }
                }
            },
            storage = storage,
        )
        val runtime = CodexAgentLoopImpl(state)
        val user = userMessage("Answer briefly.")

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume().toList()

        assertEquals(2, requests.size)
        assertEquals(listOf(user), requests[0].input)
        assertEquals(listOf(user, assistantMessage("Preparing the answer.")), requests[1].input)
        assertEquals(assistantMessage("Done."), storage.history[5])
        assertEquals(13, storage.tokenCount[5])
        assertEquals(5, storage.latestIndex())
        assertEquals(CodexAgentStateValue.AssistantMessage, state.state.value)
    }

    @Test
    fun loopRunsPreTurnCompactionBeforeSampling() = runTest {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 90,
            ),
        )
        val initialCheckpoint = storage.compaction[0]
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val responseRequests = mutableListOf<ResponsesApiRequest>()
        val compaction = ResponseItem.Compaction(encryptedContent = "pre-turn-compact")
        val final = assistantMessage("After compaction.")
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    RemoteCompactionV2Response(compactionOutput = compaction, completedResponse = null)
                }
                createResponse { request ->
                    responseRequests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, final),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )
        val runtime = CodexAgentLoopImpl(state)
        val user = userMessage("Keep this context.")

        state.appendUserMessage(user, tokenCount = 90)
        runtime.resume().toList()

        assertEquals(1, compactRequests.size)
        val compactRequest = compactRequests.single()
        assertEquals(RemoteCompactionV2Trigger.Auto, compactRequest.trigger)
        assertEquals(RemoteCompactionV2Reason.ContextLimit, compactRequest.reason)
        assertEquals(RemoteCompactionV2Phase.PreTurn, compactRequest.phase)
        assertEquals(listOf(user), compactRequest.history)
        assertEquals(listOf(user, compaction), responseRequests.single().input)
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "pre-turn-compact"), storage.history[2])
        assertEquals(final, storage.history[3])
        assertEquals(3, storage.compaction[2].historyBaseIndex)
        assertEquals(initialCheckpoint.windowNumber + 1, storage.compaction[2].windowNumber)
    }

    @Test
    fun loopRunsMidTurnCompactionBeforeFollowUpSampling() = runTest {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 20,
            ),
        )
        val compactRequests = mutableListOf<RemoteCompactionV2Request>()
        val responseRequests = mutableListOf<ResponsesApiRequest>()
        val user = userMessage("Continue until final.")
        val partial = assistantMessage("Partial answer.")
        val compaction = ResponseItem.Compaction(encryptedContent = "mid-turn-compact")
        val final = assistantMessage("Final answer.")
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request ->
                    compactRequests += request
                    RemoteCompactionV2Response(compactionOutput = compaction, completedResponse = null)
                }
                createResponse { request ->
                    responseRequests += request
                    when (responseRequests.size) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, partial),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_1",
                                    usage = TokenUsage(15, 5, 20),
                                    endTurn = false,
                                ),
                            ),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, final),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected request count ${responseRequests.size}.")
                    }
                }
            },
            storage = storage,
        )
        val runtime = CodexAgentLoopImpl(state)

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume().toList()

        assertEquals(2, responseRequests.size)
        assertEquals(listOf(user), responseRequests[0].input)
        assertEquals(listOf(user, partial), compactRequests.single().history)
        assertEquals(RemoteCompactionV2Phase.MidTurn, compactRequests.single().phase)
        assertEquals(listOf(user, compaction), responseRequests[1].input)
        assertEquals(final, storage.history[5])
    }

    @Test
    fun loopStopsAtPendingToolCallWithoutIssuingAnotherRequest() = runTest {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val toolCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{\"cmd\":\"date\"}",
            callId = "call_1",
        )
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, toolCall),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            },
            storage = storage,
        )
        val runtime = CodexAgentLoopImpl(state)

        state.appendUserMessage(userMessage("What time is it?"))
        runtime.resume().toList()
        runtime.resume().toList()

        assertEquals(1, requests.size)
        assertEquals(CodexAgentStateValue.ToolPending(listOf(toolCall)), state.state.value)
        assertIs<ResponseItem.FunctionCall>(storage.history[2])
    }
}

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private suspend fun CodexAgentStateContract.appendUserMessage(
    message: ResponseItem.Message,
    tokenCount: Long? = null,
): Int {
    require(message.role == MessageRole.User)
    return appendUserMessage(message.content).also { index ->
        if (tokenCount != null) {
            (storage as MutableCodexAgentStorage).tokenCount[index] = tokenCount
        }
    }
}
