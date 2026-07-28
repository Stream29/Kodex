package io.github.stream29.codex.lite.agentruntime.compact

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHooks
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.CompactionPhase
import io.github.stream29.codex.lite.openai.CompactionReason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.CompactionTrigger
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.TokenUsage
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

val codexAgentCompactionRuntimeTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("runtime delegates the complete AgentState contract") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val state = CodexAgentState(
            client = mockOpenAiClient {},
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime: CodexAgentRuntime = state.compactionRuntime(testModelCatalog())
        val agentState: CodexAgentStateContract = runtime

        assertSame(state.state, agentState.state)
        assertSame(state.latestIndex, agentState.latestIndex)
        assertSame(state.storage, agentState.storage)
        assertEquals(1, agentState.appendUserMessage(userMessage("Start.").content))
        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
    }

    test("runtime decorator delegates the same AgentState") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val state = CodexAgentState(
            client = mockOpenAiClient {},
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime: CodexAgentRuntime = DelegatingRuntime(CodexAgentCompactionRuntime(state, testModelCatalog()))

        assertEquals(1, runtime.appendUserMessage(userMessage("Start.").content))
        assertSame(state.state, runtime.state)
        assertSame(state.latestIndex, runtime.latestIndex)
        assertSame(state.storage, runtime.storage)
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
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = CodexAgentCompactionRuntime(state, testModelCatalog())

        state.appendUserMessage(userMessage("Start."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.resume().collect {
                firstEventCollected.complete(Unit)
                releaseConsumer.await()
            }
        }

        firstEventCollected.await()
        productionCompleted.await()

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
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = CodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
        )
        val user = userMessage("Answer briefly.")

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume().toList()

        assertEquals(2, requests.size)
        assertRequestHistory(requests[0], user)
        assertRequestHistory(requests[1], user, assistantMessage("Preparing the answer."))
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
        val hookRequests = mutableListOf<CompactionHookRequest>()
        val hooks = RecordingCompactionHooks(
            pre = { request ->
                hookRequests += request
            },
            post = { request ->
                hookRequests += request
            },
        )
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
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = CodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            compactionHooks = hooks,
        )
        val user = userMessage("Keep this context.")

        state.appendUserMessage(user, tokenCount = 90)
        val persistedTurnId = storage.settings.latestValue().turnId
        runtime.resume().toList()

        assertEquals(1, compactRequests.size)
        val compactRequest = compactRequests.single()
        assertTrue(compactRequest.turnMetadata.contains("\"trigger\":\"auto\""))
        assertTrue(compactRequest.turnMetadata.contains("\"reason\":\"context_limit\""))
        assertTrue(compactRequest.turnMetadata.contains("\"phase\":\"pre_turn\""))
        assertEquals(listOf(user, ResponseItem.CompactionTrigger), compactRequest.request.input)
        assertRequestHistory(responseRequests.single(), user, compaction)
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "pre-turn-compact"), storage.history[2])
        assertEquals(final, storage.history[3])
        assertEquals(3, storage.compaction[2].historyBaseIndex)
        assertEquals(initialCheckpoint.windowNumber + 1, storage.compaction[2].windowNumber)
        assertEquals(listOf(persistedTurnId, persistedTurnId), hookRequests.map { it.context.turnId })
        assertEquals(persistedTurnId, storage.settings.latestValue().turnId)
    }

    test("manual compaction runs observation hooks around the commit") {
        val hookRequests = mutableListOf<CompactionHookRequest>()
        val observedIndexes = mutableListOf<Int>()
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { _, _, _, _ ->
                    RemoteCompactionV2Response(
                        compactionOutput = ResponseItem.Compaction(encryptedContent = "committed"),
                        completedResponse = null,
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = CodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            compactionHooks = RecordingCompactionHooks(
                pre = { request ->
                    hookRequests += request
                    observedIndexes += storage.latestIndex()
                },
                post = { request ->
                    hookRequests += request
                    observedIndexes += storage.latestIndex()
                },
            ),
        )
        state.appendUserMessage(userMessage("Compact this."))
        val persistedTurnId = storage.settings.latestValue().turnId

        val compactedIndex = runtime.compact(
            trigger = CompactionTrigger.Manual,
            reason = CompactionReason.UserRequested,
            phase = CompactionPhase.StandaloneTurn,
        )

        assertEquals(compactedIndex, storage.latestIndex())
        assertEquals(listOf(1, compactedIndex), observedIndexes)
        assertEquals(persistedTurnId, storage.settings[compactedIndex].turnId)
        assertEquals(
            ResponseItem.ContextCompaction(encryptedContent = "committed"),
            storage.history[compactedIndex],
        )
        assertEquals(listOf(persistedTurnId, persistedTurnId), hookRequests.map { it.context.turnId })
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
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = CodexAgentCompactionRuntime(state, testModelCatalog())

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume().toList()

        assertEquals(2, responseRequests.size)
        assertRequestHistory(responseRequests[0], user)
        assertEquals(
            listOf(user, partial, ResponseItem.CompactionTrigger),
            compactRequests.single().request.input,
        )
        assertTrue(compactRequests.single().turnMetadata.contains("\"phase\":\"mid_turn\""))
        assertRequestHistory(responseRequests[1], user, compaction)
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
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = CodexAgentCompactionRuntime(state, testModelCatalog())

        state.appendUserMessage(userMessage("What time is it?"))
        runtime.resume().toList()
        runtime.resume().toList()

        assertEquals(1, requests.size)
        assertEquals(CodexAgentStateValue.ToolPending(listOf(toolCall)), state.state.value)
        assertIs<ResponseItem.FunctionCall>(storage.history[2])
    }
    }
}

private data class RecordedRemoteCompactionV2Request(
    val request: ResponsesApiRequest,
    val installationId: String?,
    val turnMetadata: String,
    val windowId: String,
)

private fun testModelCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
        codexCliStorage = CodexCliStorage(Path(".codex-lite-test-model-catalog")),
    )

private class DelegatingRuntime(
    private val delegate: CodexAgentRuntime,
) : CodexAgentRuntime by delegate

private class RecordingCompactionHooks(
    private val pre: suspend (CompactionHookRequest) -> Unit = {},
    private val post: suspend (CompactionHookRequest) -> Unit = {},
) : CompactionHooks {
    override suspend fun onPreCompact(request: CompactionHookRequest): Unit = pre(request)

    override suspend fun onPostCompact(request: CompactionHookRequest): Unit = post(request)
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

private fun assertRequestHistory(
    request: ResponsesApiRequest,
    vararg expected: ResponseItem,
) {
    assertEquals(expected.toList(), request.input.takeLast(expected.size))
}

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
