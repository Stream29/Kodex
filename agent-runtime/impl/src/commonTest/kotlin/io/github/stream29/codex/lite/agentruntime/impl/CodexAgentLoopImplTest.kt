package io.github.stream29.codex.lite.agentruntime.impl

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentEnvironment
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill
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
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val codexAgentLoopImplTest by testSuite {
    test("runtime exposes only the read only agent state properties") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val state = CodexAgentState(
            client = mockOpenAiClient {},
            storage = storage,
            contextPrefixProvider = testContextPrefixProvider,
        )
        val runtime: CodexAgentRuntime = CodexAgentLoopImpl(state)

        assertEquals(state.state, runtime.state)
        assertEquals(state.latestIndex, runtime.latestIndex)
        assertEquals(state.storage, runtime.storage)
    }

    test("loop does not wait for slow stream consumer") {
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
            contextPrefixProvider = testContextPrefixProvider,
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

    test("loop continues sampling when end turn is false") {
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
            contextPrefixProvider = testContextPrefixProvider,
        )
        val runtime = CodexAgentLoopImpl(state)
        val user = userMessage("Answer briefly.")

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume().toList()

        assertEquals(2, requests.size)
        assertEquals(requestInput(user), requests[0].input)
        assertEquals(requestInput(user, assistantMessage("Preparing the answer.")), requests[1].input)
        assertEquals(assistantMessage("Done."), storage.history[5])
        assertEquals(13, storage.tokenCount[5])
        assertEquals(5, storage.latestIndex())
        assertEquals(CodexAgentStateValue.AssistantMessage, state.state.value)
    }

    test("loop runs pre turn compaction before sampling") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 90,
            ),
        )
        val initialCheckpoint = storage.compaction[0]
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val responseRequests = mutableListOf<ResponsesApiRequest>()
        val compaction = ResponseItem.Compaction(encryptedContent = "pre-turn-compact")
        val final = assistantMessage("After compaction.")
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
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
            contextPrefixProvider = testContextPrefixProvider,
        )
        val runtime = CodexAgentLoopImpl(state)
        val user = userMessage("Keep this context.")

        state.appendUserMessage(user, tokenCount = 90)
        runtime.resume().toList()

        assertEquals(1, compactRequests.size)
        val compactRequest = compactRequests.single()
        assertTrue(compactRequest.turnMetadata.contains("\"trigger\":\"auto\""))
        assertTrue(compactRequest.turnMetadata.contains("\"reason\":\"context_limit\""))
        assertTrue(compactRequest.turnMetadata.contains("\"phase\":\"pre_turn\""))
        assertEquals(listOf(user, ResponseItem.CompactionTrigger), compactRequest.request.input)
        assertEquals(requestInput(user, compaction), responseRequests.single().input)
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "pre-turn-compact"), storage.history[2])
        assertEquals(final, storage.history[3])
        assertEquals(3, storage.compaction[2].historyBaseIndex)
        assertEquals(initialCheckpoint.windowNumber + 1, storage.compaction[2].windowNumber)
    }

    test("loop runs mid turn compaction before follow up sampling") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 20,
            ),
        )
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val responseRequests = mutableListOf<ResponsesApiRequest>()
        val user = userMessage("Continue until final.")
        val partial = assistantMessage("Partial answer.")
        val compaction = ResponseItem.Compaction(encryptedContent = "mid-turn-compact")
        val final = assistantMessage("Final answer.")
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
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
            contextPrefixProvider = testContextPrefixProvider,
        )
        val runtime = CodexAgentLoopImpl(state)

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume().toList()

        assertEquals(2, responseRequests.size)
        assertEquals(requestInput(user), responseRequests[0].input)
        assertEquals(
            listOf(user, partial, ResponseItem.CompactionTrigger),
            compactRequests.single().request.input,
        )
        assertTrue(compactRequests.single().turnMetadata.contains("\"phase\":\"mid_turn\""))
        assertEquals(requestInput(user, compaction), responseRequests[1].input)
        assertEquals(final, storage.history[5])
    }

    test("loop stops at pending tool call without issuing another request") {
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
            contextPrefixProvider = testContextPrefixProvider,
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

private data class RecordedRemoteCompactionV2Request(
    val request: ResponsesApiRequest,
    val installationId: String?,
    val turnMetadata: String,
    val windowId: String,
)

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

private val testContextPrefixProvider: AgentContextPrefixProvider =
    object : AgentContextPrefixProvider {
        override val environmentContext: EnvironmentContext =
            EnvironmentContext(
            environments = listOf(
                AgentEnvironment(
                    id = "test",
                    cwd = Path("/workspace"),
                    shell = "bash",
                ),
            ),
            currentDate = LocalDate(2026, 7, 15),
            timeZone = TimeZone.UTC,
            )

        override val availableSkills: List<AvailableSkill> = emptyList()

        override val agentMd: List<AgentsMdInstruction> = emptyList()
    }

private val testContextInput: ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(
            ContentItem.InputText(
                "<environment_context>\n" +
                    "  <cwd>/workspace</cwd>\n" +
                    "  <shell>bash</shell>\n" +
                    "  <current_date>2026-07-15</current_date>\n" +
                    "  <timezone>UTC</timezone>\n" +
                    "</environment_context>",
            ),
        ),
    )

private fun requestInput(vararg durableItems: ResponseItem): List<ResponseItem> =
    listOf(testContextInput, *durableItems)

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
