package io.github.stream29.kodex.agentruntime.decorator.compact

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.decorator.tool.toolRuntime
import io.github.stream29.kodex.agentruntime.steer.StateFlowSteerProvider
import io.github.stream29.kodex.agentruntime.steer.SteerProvider
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as AgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.contract.TokenCountKind
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.tool.contract.typedTool
import io.github.stream29.kodex.tool.toolsearch.ToolSearchEngine
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val compactionSteerTest by testSuite {
    test("empty state receives ordered input and duplicates before first sampling") {
        runTest {
            withSteerRuntime {
                val input = listOf(user("first"), user("same"), user("same"))
                queue.value = input
                val turnId = storage.settings[storage.latestIndex()].turnId

                runtime.resume()
                runtime.resume()

                assertEquals(input, userHistory())
                assertEquals(input.map { it.toResponseHistoryItems().single() }, requests.first().input.takeLast(3))
                assertEquals(turnId, storage.settings[storage.latestIndex()].turnId)
                assertTrue(queue.value.isEmpty())
            }
        }
    }

    test("assistant state accepts steer and retains the existing turn identity rule") {
        runTest {
            withSteerRuntime {
                state.injectHistory(listOf(StableAssistantMessage(content("finished"))))
                val turnId = storage.settings[storage.latestIndex()].turnId
                queue.value = listOf(user("continue"))

                runtime.resume()

                assertEquals(listOf(user("continue")), userHistory())
                assertNotEquals(turnId, storage.settings[storage.latestIndex()].turnId)
            }
        }
    }

    test("agent-message input is persisted unchanged without becoming a user message") {
        runTest {
            withSteerRuntime {
                val input = StableAgentMessage(
                    author = "/root/worker",
                    recipient = "/root",
                    content = listOf(AgentMessageInputContent.InputText("done")),
                )
                queue.value = listOf(input)

                runtime.resume()

                assertEquals(input, storage.index[1])
                assertTrue(userHistory().isEmpty())
                assertTrue(queue.value.isEmpty())
            }
        }
    }

    test("retracted input is not injected or recreated") {
        runTest {
            withSteerRuntime {
                state.appendUserMessage(content("initial"))
                queue.value = listOf(user("retracted"))
                queue.value = emptyList()

                runtime.resume()

                assertEquals(listOf(user("initial")), userHistory())
                assertEquals(1, requests.size)
            }
        }
    }

    test("continued response receives late input inside the inner sampling loop") {
        runTest {
            withSteerRuntime(
                response = { count, _ -> completedFlow(endTurn = count != 1) },
                wrapState = { delegate, queue ->
                    object : AgentState by delegate {
                        override suspend fun requestResponseApi(): RequestFinish =
                            delegate.requestResponseApi().also { finish ->
                                if (finish == RequestFinish.Continue) queue.value = listOf(user("adjust"))
                            }
                    }
                },
            ) {
                state.appendUserMessage(content("initial"))

                runtime.resume()

                assertEquals(2, requests.size)
                assertEquals(listOf("initial", "adjust"), requests[1].userTexts())
                assertEquals(listOf(user("initial"), user("adjust")), userHistory())
            }
        }
    }

    test("retryable response receives late input before retrying") {
        runTest {
            withSteerRuntime(
                response = { count, _ -> if (count == 1) emptyFlow() else completedFlow() },
                wrapState = { delegate, queue ->
                    object : AgentState by delegate {
                        override suspend fun requestResponseApi(): RequestFinish =
                            delegate.requestResponseApi().also { finish ->
                                if (finish == RequestFinish.Retryable) queue.value = listOf(user("retry with this"))
                            }
                    }
                },
            ) {
                state.appendUserMessage(content("initial"))

                runtime.resume()

                assertEquals(2, requests.size)
                assertEquals(listOf("initial", "retry with this"), requests[1].userTexts())
            }
        }
    }

    test("finished response with late input samples again before returning") {
        runTest {
            withSteerRuntime(
                response = { count, _ ->
                    if (count == 1) flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistant("first", MessagePhase.FinalAnswer)),
                        ResponsesStreamEvent.Completed(Response(id = "first", endTurn = true)),
                    ) else completedFlow()
                },
                wrapState = { delegate, queue ->
                    object : AgentState by delegate {
                        var invocation = 0
                        override suspend fun requestResponseApi(): RequestFinish =
                            delegate.requestResponseApi().also {
                                if (++invocation == 1) queue.value = listOf(user("late"))
                            }
                    }
                },
            ) {
                state.appendUserMessage(content("initial"))
                val turnId = storage.settings[storage.latestIndex()].turnId

                runtime.resume()

                assertEquals(2, requests.size)
                assertEquals(listOf("initial", "late"), requests[1].userTexts())
                assertNotEquals(turnId, storage.settings[storage.latestIndex()].turnId)
            }
        }
    }

    test("steer preempts a multi-item request and waits for cleanup before claiming") {
        runTest {
            val generating = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            withSteerRuntime(response = { count, _ ->
                if (count == 1) flow {
                    emit(ResponsesStreamEvent.OutputItemDone(0, assistant("committed", MessagePhase.Commentary)))
                    emit(ResponsesStreamEvent.OutputItemAdded(1, toolCall(arguments = "{")))
                    generating.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cleaning.complete(Unit)
                        withContext(NonCancellable) { releaseCleanup.await() }
                    }
                } else completedFlow()
            }) {
                state.appendUserMessage(content("initial"))
                val turnId = storage.settings[storage.latestIndex()].turnId
                val running = async { runtime.resume() }
                generating.await()
                queue.value = listOf(user("first"))
                cleaning.await()

                try {
                    assertEquals(1, requests.size)
                    assertEquals(listOf(user("first")), queue.value)
                    assertIs<KodexAgentStateValue.RequestResponse>(state.state.value)
                    queue.update { it + listOf(user("second"), user("second")) }
                } finally {
                    releaseCleanup.complete(Unit)
                }
                running.await()

                assertEquals(2, requests.size)
                assertEquals(listOf("initial", "first", "second", "second"), requests[1].userTexts())
                assertFalse(requests[1].input.any { it is ResponseItem.ToolCall })
                assertEquals(1, storage.index.indexesIn(0..storage.latestIndex())
                    .count { storage.index[it] is StableAssistantMessage })
                assertEquals(turnId, storage.settings[storage.latestIndex()].turnId)
                assertTrue(queue.value.isEmpty())
                assertEquals(KodexAgentStateValue.UserMessage, state.state.value)
            }
        }
    }

    test("a committed tool call winning the race leaves steer queued until completion") {
        runTest {
            val committed = CompletableDeferred<Unit>()
            withSteerRuntime(response = { count, _ ->
                if (count == 1) flow {
                    emit(ResponsesStreamEvent.OutputItemDone(0, toolCall()))
                    committed.complete(Unit)
                    awaitCancellation()
                } else completedFlow()
            }) {
                state.appendUserMessage(content("initial"))
                val running = async { runtime.resume() }
                committed.await()
                queue.value = listOf(user("after tool"))
                running.await()

                val pending = assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
                assertEquals(listOf(user("after tool")), queue.value)
                assertEquals(1, requests.size)
                assertEquals(listOf(user("initial")), userHistory())
                val call = assertIs<PendingFunctionToolEvent>(pending.events.single())
                state.completeToolCall(
                    StableTextToolEvent(
                        callId = call.callId,
                        itemId = call.itemId,
                        name = call.name,
                        arguments = call.arguments,
                        result = "done",
                    ),
                )
                runtime.resume()

                assertEquals(2, requests.size)
                assertEquals(listOf("initial", "after tool"), requests[1].userTexts())
                assertEquals(1, requests[1].input.filterIsInstance<ResponseItem.FunctionCall>().size)
                assertEquals(1, requests[1].input.filterIsInstance<ResponseItem.FunctionCallOutput>().size)
                assertTrue(queue.value.isEmpty())
            }
        }
    }

    test("entering with pending calls does not claim input or issue a request") {
        runTest {
            withSteerRuntime {
                state.appendUserMessage(content("initial"))
                state.modify { storage ->
                    storage.unstable[storage.latestIndex() + 1] = listOf(
                        PendingFunctionToolEvent(
                            name = "test_tool",
                            arguments = JsonObject(emptyMap()),
                            callId = "pending",
                        ),
                    )
                }
                queue.value = listOf(user("retain"))

                runtime.resume()

                assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
                assertEquals(listOf(user("retain")), queue.value)
                assertTrue(requests.isEmpty())
            }
        }
    }

    test("retraction after wake causes no delivery and no repeated stale preemption") {
        runTest {
            val generating = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            withSteerRuntime(response = { count, _ ->
                if (count == 1) flow {
                    generating.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cleaning.complete(Unit)
                        withContext(NonCancellable) { releaseCleanup.await() }
                    }
                } else completedFlow()
            }) {
                state.appendUserMessage(content("initial"))
                val running = async { runtime.resume() }
                generating.await()
                queue.value = listOf(user("retract"))
                cleaning.await()
                try {
                    queue.value = emptyList()
                } finally {
                    releaseCleanup.complete(Unit)
                }
                running.await()

                assertEquals(2, requests.size)
                assertEquals(listOf(user("initial")), userHistory())
                assertTrue(queue.value.isEmpty())
            }
        }
    }

    test("stop cancels the sampling wait without consuming newly queued input") {
        runTest {
            val generating = CompletableDeferred<Unit>()
            withSteerRuntime(response = { _, _ -> flow {
                generating.complete(Unit)
                awaitCancellation()
            } }) {
                state.appendUserMessage(content("initial"))
                val running = async { runtime.resume() }
                generating.await()

                running.cancel()
                queue.value = listOf(user("keep"))
                assertFailsWith<CancellationException> { running.await() }

                assertEquals(listOf(user("keep")), queue.value)
                assertEquals(listOf(user("initial")), userHistory())
                assertEquals(KodexAgentStateValue.UserMessage, state.state.value)
            }
        }
    }

    test("a request cleanup failure is not swallowed as successful preemption") {
        runTest {
            val generating = CompletableDeferred<Unit>()
            val failure = IllegalStateException("cleanup failed")
            withSteerRuntime(response = { _, _ -> flow {
                generating.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    throw failure
                }
            } }) {
                state.appendUserMessage(content("initial"))
                supervisorScope {
                    val running = async { runtime.resume() }
                    generating.await()
                    queue.value = listOf(user("retain on failure"))
                    assertEquals(failure.message, assertFailsWith<IllegalStateException> { running.await() }.message)
                }

                assertEquals(1, requests.size)
                assertEquals(listOf(user("retain on failure")), queue.value)
                assertEquals(KodexAgentStateValue.UserMessage, state.state.value)
            }
        }
    }

    test("unrelated request cancellation remains cancellation even with pending steer") {
        runTest {
            val cancellation = CancellationException("provider cancelled")
            withSteerRuntime(response = { _, _ -> flow {
                throw cancellation
            } }) {
                state.appendUserMessage(content("initial"))

                assertEquals(
                    cancellation.message,
                    assertFailsWith<CancellationException> { runtime.resume() }.message,
                )
                assertEquals(1, requests.size)
            }
        }
    }

    test("claimed input is persisted before stop finishes and no request starts") {
        runTest {
            val claimed = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            withSteerRuntime(wrapState = { delegate, _ ->
                object : AgentState by delegate {
                    override suspend fun injectHistory(events: List<StableIndexEvent.Steerable>): Int {
                        claimed.complete(Unit)
                        releaseWrite.await()
                        return delegate.injectHistory(events)
                    }
                }
            }) {
                queue.value = listOf(user("claimed"))
                val running = async { runtime.resume() }
                claimed.await()
                try {
                    assertTrue(queue.value.isEmpty())
                    running.cancel()
                    assertFalse(running.isCompleted)
                } finally {
                    releaseWrite.complete(Unit)
                }
                assertFailsWith<CancellationException> { running.await() }

                assertEquals(listOf(user("claimed")), userHistory())
                assertTrue(requests.isEmpty())
            }
        }
    }

    test("delivery failure preserves committed prefix without replay or sampling") {
        runTest {
            val failure = IllegalStateException("write failed")
            withSteerRuntime(wrapState = { delegate, _ ->
                object : AgentState by delegate {
                    override suspend fun injectHistory(events: List<StableIndexEvent.Steerable>): Int {
                        delegate.injectHistory(events.take(1))
                        throw failure
                    }
                }
            }) {
                queue.value = listOf(user("committed"), user("not committed"))

                assertEquals(failure.message, assertFailsWith<IllegalStateException> { runtime.resume() }.message)

                assertEquals(listOf(user("committed")), userHistory())
                assertTrue(queue.value.isEmpty())
                assertTrue(requests.isEmpty())
            }
        }
    }

    test("input queued during automatic compaction is included in the next request") {
        runTest {
            val compacting = CompletableDeferred<Unit>()
            val releaseCompact = CompletableDeferred<Unit>()
            withSteerRuntime(
                settings = KodexAgentSettings(OpenAiModelId("test-model"), autoCompactionTokenLimit = 20),
                compact = {
                    compacting.complete(Unit)
                    releaseCompact.await()
                    compactResponse()
                },
            ) {
                state.appendUserMessage(content("initial"))
                storage.tokenCount[state.latestIndex.value] = TokenCountSnapshot(TokenCountKind.Response, 20L)
                val running = async { runtime.resume() }
                compacting.await()
                queue.value = listOf(user("during compact"))

                assertEquals(listOf(user("during compact")), queue.value)
                assertEquals(KodexAgentStateValue.Compacting, state.state.value)
                releaseCompact.complete(Unit)
                running.await()

                assertEquals(1, compactRequests.size)
                assertEquals(1, requests.size)
                assertEquals(listOf("initial", "during compact"), requests.single().userTexts())
                assertTrue(queue.value.isEmpty())
            }
        }
    }

    test("input queued during mid-turn compaction is included before continuation") {
        runTest {
            val compacting = CompletableDeferred<Unit>()
            val releaseCompact = CompletableDeferred<Unit>()
            withSteerRuntime(
                settings = KodexAgentSettings(OpenAiModelId("test-model"), autoCompactionTokenLimit = 20),
                response = { count, _ ->
                    if (count == 1) flowOf(
                        ResponsesStreamEvent.Completed(
                            Response(id = "continue", endTurn = false, usage = TokenUsage(15, 5, 20)),
                        ),
                    ) else completedFlow()
                },
                compact = {
                    compacting.complete(Unit)
                    releaseCompact.await()
                    compactResponse()
                },
            ) {
                state.appendUserMessage(content("initial"))
                val running = async { runtime.resume() }
                compacting.await()
                try {
                    queue.value = listOf(user("mid-turn"))
                    assertEquals(1, requests.size)
                    assertEquals(KodexAgentStateValue.Compacting, state.state.value)
                } finally {
                    releaseCompact.complete(Unit)
                }
                running.await()

                assertEquals(1, compactRequests.size)
                assertEquals(2, requests.size)
                assertEquals(listOf("initial", "mid-turn"), requests[1].userTexts())
                assertTrue(queue.value.isEmpty())
            }
        }
    }

    test("steer preemption neither spends nor resets the existing retry budget") {
        runTest {
            val generating = CompletableDeferred<Unit>()
            withSteerRuntime(response = { count, _ ->
                if (count == CompactionRetryPolicy.maxRetries + 1) flow {
                    generating.complete(Unit)
                    awaitCancellation()
                } else emptyFlow()
            }) {
                state.appendUserMessage(content("initial"))
                supervisorScope {
                    val running = async { runtime.resume() }
                    generating.await()
                    queue.value = listOf(user("adjust"))
                    assertFailsWith<AgentResponseRetryLimitExceededException> { running.await() }
                }

                assertEquals(CompactionRetryPolicy.maxRetries + 2, requests.size)
                assertEquals(listOf("initial", "adjust"), requests.last().userTexts())
                assertEquals(listOf(user("initial"), user("adjust")), userHistory())
            }
        }
    }

    test("finished response with new input begins a fresh retry budget") {
        runTest {
            val firstFinish = CompactionRetryPolicy.maxRetries + 1
            withSteerRuntime(
                response = { count, _ ->
                    if (count == firstFinish || count == firstFinish * 2) completedFlow() else emptyFlow()
                },
                wrapState = { delegate, queue ->
                    var finishes = 0
                    object : AgentState by delegate {
                        override suspend fun requestResponseApi(): RequestFinish =
                            delegate.requestResponseApi().also { finish ->
                                if (finish == RequestFinish.Finish && ++finishes == 1) {
                                    queue.value = listOf(user("next"))
                                }
                            }
                    }
                },
            ) {
                state.appendUserMessage(content("initial"))

                runtime.resume()

                assertEquals(firstFinish * 2, requests.size)
                assertEquals(listOf("initial", "next"), requests.last().userTexts())
                assertTrue(queue.value.isEmpty())
            }
        }
    }

    test("tool runtime finishes one handler before delivering queued steer and resampling") {
        runTest {
            val handling = CompletableDeferred<Unit>()
            val releaseTool = CompletableDeferred<Unit>()
            var executions = 0
            withSteerRuntime(response = { count, _ ->
                if (count == 1) flowOf(
                    ResponsesStreamEvent.OutputItemDone(0, toolCall()),
                    ResponsesStreamEvent.Completed(Response(id = "call", endTurn = false)),
                ) else completedFlow()
            }) {
                val tool = typedTool(
                    spec = ResponsesApiTool(
                        name = "test_tool",
                        description = "test",
                        parameters = PropertyBuilder().obj { additionalProperties = false },
                    ),
                    select = { it as? PendingFunctionToolEvent },
                ) { pending ->
                    executions += 1
                    handling.complete(Unit)
                    releaseTool.await()
                    StableTextToolEvent(
                        name = pending.name,
                        arguments = pending.arguments,
                        callId = pending.callId,
                        result = "executed once",
                    )
                }
                try {
                    val composed = runtime.toolRuntime(
                        fixedTools = listOf(tool),
                        dynamicTools = MutableStateFlow(emptyList()),
                        toolSearch = MutableStateFlow(ToolSearchEngine(emptyList())),
                        logger = KotlinLogging.logger {},
                    )
                    state.appendUserMessage(content("initial"))
                    val running = async { composed.resume() }
                    handling.await()
                    try {
                        queue.value = listOf(user("after execution"))
                        assertEquals(1, requests.size)
                        assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
                        assertEquals(listOf(user("after execution")), queue.value)
                    } finally {
                        releaseTool.complete(Unit)
                    }
                    running.await()

                    assertEquals(1, executions)
                    assertEquals(2, requests.size)
                    val input = requests[1].input
                    val callIndex = input.indexOfFirst { it is ResponseItem.FunctionCall }
                    val outputIndex = input.indexOfFirst { it is ResponseItem.FunctionCallOutput }
                    val steerIndex = input.indexOf(user("after execution").toResponseHistoryItems().single())
                    assertEquals(1, input.filterIsInstance<ResponseItem.FunctionCall>().size)
                    assertEquals(1, input.filterIsInstance<ResponseItem.FunctionCallOutput>().size)
                    assertTrue(callIndex >= 0 && callIndex < outputIndex && outputIndex < steerIndex)
                    assertEquals(listOf("initial", "after execution"), requests[1].userTexts())
                    assertTrue(queue.value.isEmpty())
                } finally {
                    tool.close()
                }
            }
        }
    }

    test("manual compaction neither waits for nor consumes pending steer") {
        runTest {
            withSteerRuntime {
                state.appendUserMessage(content("initial"))
                queue.value = listOf(user("retain"))

                runtime.compact(
                    trigger = CompactionTrigger.Manual,
                    reason = CompactionReason.UserRequested,
                    phase = CompactionPhase.StandaloneTurn,
                )

                assertEquals(listOf(user("retain")), queue.value)
                assertEquals(1, compactRequests.size)
                assertTrue(requests.isEmpty())
            }
        }
    }

    test("sampling completion leaves no active steer waiter") {
        runTest {
            var activeWaiters = 0
            withSteerRuntime(wrapProvider = { provider ->
                object : SteerProvider by provider {
                    override suspend fun waitForSteer() {
                        activeWaiters += 1
                        try {
                            provider.waitForSteer()
                        } finally {
                            activeWaiters -= 1
                        }
                    }
                }
            }) {
                state.appendUserMessage(content("initial"))

                runtime.resume()

                assertEquals(0, activeWaiters)
                assertEquals(1, requests.size)
            }
        }
    }
}

private class SteerFixture(
    val state: AgentState,
    val storage: InMemoryKodexAgentStorage,
    val runtime: KodexAgentCompactionRuntime,
    val queue: MutableStateFlow<List<StableIndexEvent.Steerable>>,
    val requests: List<ResponsesApiRequest>,
    val compactRequests: List<ResponsesApiRequest>,
) {
    suspend fun userHistory(): List<StableUserMessage> =
        storage.index.indexesIn(0..storage.latestIndex())
            .mapNotNull { storage.index[it] as? StableUserMessage }
}

private suspend fun CoroutineScope.withSteerRuntime(
    settings: KodexAgentSettings = KodexAgentSettings(OpenAiModelId("test-model")),
    response: suspend (Int, ResponsesApiRequest) -> Flow<ResponsesStreamEvent> = { _, _ -> completedFlow() },
    compact: suspend (ResponsesApiRequest) -> RemoteCompactionV2Response = { compactResponse() },
    wrapState: (AgentState, MutableStateFlow<List<StableIndexEvent.Steerable>>) -> AgentState = { state, _ -> state },
    wrapProvider: (SteerProvider) -> SteerProvider = { it },
    block: suspend SteerFixture.() -> Unit,
) {
    val storage = InMemoryKodexAgentStorage(settings)
    val queue = MutableStateFlow(emptyList<StableIndexEvent.Steerable>())
    val requests = mutableListOf<ResponsesApiRequest>()
    val compactRequests = mutableListOf<ResponsesApiRequest>()
    val state = KodexAgentState(
        client = mockOpenAiClient {
            createResponse { request ->
                requests += request
                response(requests.size, request)
            }
            createRemoteCompactionV2Response { request ->
                compactRequests += request
                compact(request)
            }
        },
        storage = storage,
        contextSettings = TestAgentContextSettings,
        mcpService = TestMcpService(),
    )
    val modelCatalog = OpenAiModelCatalog(
        client = mockOpenAiClient { listModels { OpenAiResult.Success(ModelsResponse()) } },
    )
    try {
        val runtime = wrapState(state, queue).compactionRuntime(
            modelCatalog = modelCatalog,
            logger = KotlinLogging.logger {},
            steerProvider = wrapProvider(StateFlowSteerProvider(queue)),
        )
        SteerFixture(state, storage, runtime, queue, requests, compactRequests).block()
    } finally {
        modelCatalog.close()
        state.cancelAndJoin()
    }
}

private fun completedFlow(endTurn: Boolean = true): Flow<ResponsesStreamEvent> =
    flowOf(ResponsesStreamEvent.Completed(Response(id = "test-response", endTurn = endTurn)))

private fun compactResponse(): RemoteCompactionV2Response =
    RemoteCompactionV2Response(
        compactionOutput = ResponseItem.Compaction(encryptedContent = "test-compaction"),
        completedResponse = null,
    )

private fun content(text: String): List<ContentItem> = listOf(ContentItem.InputText(text))

private fun user(text: String): StableUserMessage = StableUserMessage(content(text))

private fun assistant(text: String, phase: MessagePhase): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
        phase = phase,
    )

private fun toolCall(arguments: String = "{}"): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(name = "test_tool", callId = "test-call", arguments = arguments)

private fun ResponsesApiRequest.userTexts(): List<String> =
    input.filterIsInstance<ResponseItem.Message>().filter { it.role == MessageRole.User }
        .flatMap { it.content.filterIsInstance<ContentItem.InputText>().map(ContentItem.InputText::text) }
        // Request assembly includes a separate generated environment message.
        .filterNot { it.startsWith("<environment_context>") }
