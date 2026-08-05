package io.github.stream29.kodex.agentruntime.decorator.compact

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as KodexAgentStateContract
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.kodex.hook.contract.compaction.CompactionHooks
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

val kodexAgentCompactionRuntimeTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("runtime delegates the complete AgentState contract") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
        val state = KodexAgentState(
            client = mockOpenAiClient {},
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime: ResumableAgentLayer = state.compactionRuntime(
            modelCatalog = testModelCatalog(),
            logger = TestLogger,
        )
        val agentState: KodexAgentStateContract = runtime

        assertSame(state.state, agentState.state)
        assertSame(state.latestIndex, agentState.latestIndex)
        assertSame(state.storage, agentState.storage)
        assertEquals(1, agentState.appendUserMessage(userMessage("Start.").content))
        assertEquals(KodexAgentStateValue.UserMessage, state.state.value)
    }

    test("runtime decorator delegates the same AgentState") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
        val state = KodexAgentState(
            client = mockOpenAiClient {},
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime: ResumableAgentLayer = DelegatingRuntime(
            KodexAgentCompactionRuntime(
                delegate = state,
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            ),
        )

        assertEquals(1, runtime.appendUserMessage(userMessage("Start.").content))
        assertSame(state.state, runtime.state)
        assertSame(state.latestIndex, runtime.latestIndex)
        assertSame(state.storage, runtime.storage)
    }

    test("loop completes without an external event collector") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
        val productionCompleted = CompletableDeferred<Unit>()
        val delta = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "x",
        )
        val state = KodexAgentState(
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
        val runtime = KodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            logger = TestLogger,
        )

        state.appendUserMessage(userMessage("Start."))
        runtime.resume()
        productionCompleted.await()
    }

    test("loop continues sampling when end turn is false") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val state = KodexAgentState(
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
        val runtime = KodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            logger = TestLogger,
        )
        val user = userMessage("Answer briefly.")

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume()

        assertEquals(2, requests.size)
        assertRequestHistory(requests[0], user)
        assertRequestHistory(requests[1], user, assistantMessage("Preparing the answer."))
        assertEquals(
            StableCleanEvent.AssistantMessage(assistantMessage("Done.").content),
            storage.stable[5],
        )
        assertEquals(13, storage.tokenCount[5])
        assertEquals(5, storage.latestIndex())
        assertEquals(KodexAgentStateValue.AssistantMessage, state.state.value)
    }

    test("loop runs pre turn compaction before sampling") {
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(
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
        val state = KodexAgentState(
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
        val runtime = KodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            logger = TestLogger,
            compactionHooks = hooks,
        )
        val user = userMessage("Keep this context.")

        state.appendUserMessage(user, tokenCount = 90)
        val persistedTurnId = storage.settings.latestValue().turnId
        runtime.resume()

        assertEquals(1, compactRequests.size)
        val compactRequest = compactRequests.single()
        assertTrue(compactRequest.turnMetadata.contains("\"trigger\":\"auto\""))
        assertTrue(compactRequest.turnMetadata.contains("\"reason\":\"context_limit\""))
        assertTrue(compactRequest.turnMetadata.contains("\"phase\":\"pre_turn\""))
        assertEquals(listOf(user, ResponseItem.CompactionTrigger), compactRequest.request.input)
        assertRequestHistory(responseRequests.single(), user, compaction)
        assertEquals(StableCleanEvent.ContextCompaction, storage.stable[2])
        assertEquals(compaction, storage.compaction[2].compaction)
        assertEquals(StableCleanEvent.AssistantMessage(final.content), storage.stable[3])
        assertEquals(3, storage.compaction[2].historyBaseIndex)
        assertEquals(initialCheckpoint.windowNumber + 1, storage.compaction[2].windowNumber)
        assertEquals(listOf(persistedTurnId, persistedTurnId), hookRequests.map { it.context.turnId })
        assertEquals(persistedTurnId, storage.settings.latestValue().turnId)
    }

    test("manual compaction runs observation hooks around the commit") {
        val hookRequests = mutableListOf<CompactionHookRequest>()
        val observedIndexes = mutableListOf<Int>()
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
        val state = KodexAgentState(
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
        val runtime = KodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            logger = TestLogger,
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
            StableCleanEvent.ContextCompaction,
            storage.stable[compactedIndex],
        )
        assertEquals(
            ResponseItem.Compaction(encryptedContent = "committed"),
            storage.compaction[compactedIndex].compaction,
        )
        assertEquals(listOf(persistedTurnId, persistedTurnId), hookRequests.map { it.context.turnId })
    }

    test("loop runs mid turn compaction before follow up sampling") {
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(
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
        val state = KodexAgentState(
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
        val runtime = KodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            logger = TestLogger,
        )

        state.appendUserMessage(user, tokenCount = 1)
        runtime.resume()

        assertEquals(2, responseRequests.size)
        assertRequestHistory(responseRequests[0], user)
        assertEquals(
            listOf(user, partial, ResponseItem.CompactionTrigger),
            compactRequests.single().request.input,
        )
        assertTrue(compactRequests.single().turnMetadata.contains("\"phase\":\"mid_turn\""))
        assertRequestHistory(responseRequests[1], user, compaction)
        assertEquals(StableCleanEvent.AssistantMessage(final.content), storage.stable[5])
    }

    test("loop stops at pending tool call without issuing another request") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val toolCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{\"cmd\":\"date\"}",
            callId = "call_1",
        )
        val state = KodexAgentState(
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
        val runtime = KodexAgentCompactionRuntime(
            delegate = state,
            modelCatalog = testModelCatalog(),
            logger = TestLogger,
        )
        val pending = PendingCommandExecutionToolEvent(
            callId = "call_1",
            action = PendingCommandExecutionAction.ExecCommand(
                ExecCommandArguments(command = "date"),
            ),
        )

        state.appendUserMessage(userMessage("What time is it?"))
        runtime.resume()
        runtime.resume()

        assertEquals(1, requests.size)
        assertEquals(KodexAgentStateValue.ToolPending(listOf(pending)), state.state.value)
        assertEquals(pending, storage.unstable[2].single())
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
        codexCliStorage = CodexCliStorage(Path(".kodex-test-model-catalog")),
    )

private val TestLogger = KotlinLogging.logger {}

private class DelegatingRuntime(
    private val delegate: ResumableAgentLayer,
) : ResumableAgentLayer by delegate

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

private suspend fun KodexAgentStateContract.appendUserMessage(
    message: ResponseItem.Message,
    tokenCount: Long? = null,
): Int {
    require(message.role == MessageRole.User)
    return appendUserMessage(message.content).also { index ->
        if (tokenCount != null) {
            (storage as MutableKodexAgentStorage).tokenCount[index] = tokenCount
        }
    }
}
