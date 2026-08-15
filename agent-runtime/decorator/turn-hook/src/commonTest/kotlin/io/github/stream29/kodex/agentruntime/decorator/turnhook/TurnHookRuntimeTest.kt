package io.github.stream29.kodex.agentruntime.decorator.turnhook

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.decorator.compact.compactionRuntime
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.hook.contract.turn.HookPromptFragment
import io.github.stream29.kodex.hook.contract.turn.StopRequest
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.contract.turn.TurnHooks
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

val turnHookRuntimeTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("user prompt hook runs before delegated resume and stores additional context") {
        val requests = mutableListOf<ResponsesApiRequest>()
        val hookRequests = mutableListOf<UserPromptSubmitRequest>()
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage("done")),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    userPrompt = { request ->
                        hookRequests += request
                        UserPromptSubmitResult.Continue(listOf("hook context"))
                    },
                ),
            )

        runtime.appendUserMessage(
            listOf(
                ContentItem.InputText("hello"),
                ContentItem.InputImage("data:image/png;base64,AA=="),
                ContentItem.InputText(" again"),
            ),
        )
        assertEquals(Unit, runtime.resume())

        assertEquals("hello again", hookRequests.single().prompt)
        assertEquals(1, requests.size)
        assertEquals(hookRequests.single().context.turnId, storage.settings.latestValue().turnId)
        val history = storage.stableHistoryItems()
        assertEquals(3, history.size)
        assertEquals(MessageRole.User, assertIs<ResponseItem.Message>(history[0]).role)
        assertEquals("hook context", assertIs<ResponseItem.Message>(history[1]).inputText())
        assertEquals(MessageRole.Developer, assertIs<ResponseItem.Message>(history[1]).role)
        assertEquals(MessageRole.Assistant, assertIs<ResponseItem.Message>(history[2]).role)
    }

    test("user prompt hook reads the latest user message") {
        val hookRequests = mutableListOf<UserPromptSubmitRequest>()
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage("done")),
                        ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    userPrompt = { request ->
                        hookRequests += request
                        UserPromptSubmitResult.Continue()
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("actual prompt")))
        runtime.injectHistory(
            listOf(
                StableCleanEvent.UserMessage(
                    content = listOf(ContentItem.InputText("selected skill instructions")),
                ),
            ),
        )
        assertEquals(Unit, runtime.resume())

        assertEquals("selected skill instructions", hookRequests.single().prompt)
    }

    test("blocked user prompt retains persisted input and skips the model request") {
        var requestCount = 0
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    requestCount += 1
                    error("A blocked prompt must not reach OpenAI.")
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    userPrompt = {
                        UserPromptSubmitResult.Stop(
                            reason = "blocked",
                            additionalContexts = listOf("why this was blocked"),
                        )
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("do not store")))
        assertEquals(Unit, runtime.resume())

        assertEquals(0, requestCount)
        val history = storage.stableHistoryItems()
        assertEquals(2, history.size)
        assertEquals(MessageRole.User, assertIs<ResponseItem.Message>(history[0]).role)
        assertEquals(MessageRole.Developer, assertIs<ResponseItem.Message>(history[1]).role)
        assertEquals("why this was blocked", assertIs<ResponseItem.Message>(history[1]).inputText())
        assertEquals(KodexAgentStateValue.UserMessage, state.state.value)
    }

    test("pending tools stop at the state boundary without running the stop hook") {
        var stopCalls = 0
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.FunctionCall(
                                name = "host_only",
                                arguments = "{}",
                                callId = "call_host_only",
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    stop = {
                        stopCalls += 1
                        StopResult.Finish
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("ask the host")))
        runtime.resume()

        assertEquals(0, stopCalls)
        assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
    }

    test("request user input runs the stop hook and remains pending when accepted") {
        val stopRequests = mutableListOf<StopRequest>()
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage("Choose a scope.")),
                        ResponsesStreamEvent.OutputItemDone(1, assistantMessage("  ")),
                        ResponsesStreamEvent.OutputItemDone(2, requestUserInputCall()),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    stop = { request ->
                        stopRequests += request
                        StopResult.Finish
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("ask me")))
        runtime.resume()

        assertEquals(1, stopRequests.size)
        assertEquals(false, stopRequests.single().stopHookActive)
        assertEquals("Choose a scope.", stopRequests.single().lastAssistantMessage)
        val pending = assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
        assertIs<PendingRequestUserInputToolEvent>(pending.events.single())
        assertEquals(
            emptyList(),
            storage.stableEvents().filterIsInstance<StableRequestUserInputToolEvent>(),
        )

        state.updateSettings(
            storage.settings.latestValue().copy(
                requestUserInputMode = RequestUserInputMode.NoQuestion,
            ),
        )

        val afterModeSwitch = assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
        assertEquals(pending.events, afterModeSwitch.events)
    }

    test("request questions supply stop text and empty continuation remains pending") {
        var requestCount = 0
        val stopRequests = mutableListOf<StopRequest>()
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    requestCount += 1
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            requestUserInputCall(
                                arguments = """
                                    {
                                      "questions": [
                                        {"id": "scope", "header": "Scope", "question": "Which scope?"},
                                        {"id": "target", "header": "Target", "question": "Which target?"}
                                      ]
                                    }
                                """.trimIndent(),
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    stop = { request ->
                        stopRequests += request
                        StopResult.Continue(emptyList())
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("ask me")))
        runtime.resume()

        assertEquals(1, requestCount)
        assertEquals(1, stopRequests.size)
        assertEquals("Which scope?\nWhich target?", stopRequests.single().lastAssistantMessage)
        assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
        assertEquals(
            emptyList(),
            storage.stableEvents().filterIsInstance<StableRequestUserInputToolEvent>(),
        )
    }

    test("stop continuation cancels request user input before resuming the model") {
        val requests = mutableListOf<ResponsesApiRequest>()
        val stopRequests = mutableListOf<StopRequest>()
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    if (requests.size == 1) {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, requestUserInputCall()),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    } else {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage("continued")),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )
                    }
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    stop = { request ->
                        stopRequests += request
                        if (stopRequests.size == 1) {
                            StopResult.Continue(
                                listOf(HookPromptFragment("Continue without asking.", "request-input")),
                            )
                        } else {
                            StopResult.Finish
                        }
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("ask me")))
        runtime.resume()

        assertEquals(2, requests.size)
        assertEquals(listOf(false, true), stopRequests.map(StopRequest::stopHookActive))
        assertEquals(
            listOf("Which scope?", "continued"),
            stopRequests.map(StopRequest::lastAssistantMessage),
        )
        assertEquals(stopRequests[0].context.turnId, stopRequests[1].context.turnId)
        val completed = storage.stableEvents()
            .filterIsInstance<StableRequestUserInputToolEvent>()
            .single()
        assertEquals(
            StableRequestUserInputResult.Failure("request_user_input cancelled by Stop hook"),
            completed.result,
        )
        assertEquals(
            true,
            requests[1].input.any { item -> item is ResponseItem.FunctionCallOutput },
        )
        assertEquals(KodexAgentStateValue.AssistantMessage, state.state.value)
    }

    test("stop result cancels request user input without another model request") {
        var requestCount = 0
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    requestCount += 1
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, requestUserInputCall()),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    stop = { StopResult.Stop("stop now") },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("ask me")))
        runtime.resume()

        assertEquals(1, requestCount)
        assertEquals(KodexAgentStateValue.ToolCompleted, state.state.value)
        val completed = storage.stableEvents()
            .filterIsInstance<StableRequestUserInputToolEvent>()
            .single()
        assertEquals(
            StableRequestUserInputResult.Failure("request_user_input cancelled by Stop hook"),
            completed.result,
        )
    }

    test("mixed pending tools do not run the stop hook") {
        var stopCalls = 0
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, requestUserInputCall()),
                        ResponsesStreamEvent.OutputItemDone(
                            1,
                            ResponseItem.FunctionCall(
                                name = "host_only",
                                arguments = "{}",
                                callId = "call_host_only",
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    stop = {
                        stopCalls += 1
                        StopResult.Finish
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("run both")))
        runtime.resume()

        assertEquals(0, stopCalls)
        val pending = assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
        assertEquals(2, pending.events.size)
    }

    test("stop hook continuation stays in one turn and persists its wire message") {
        val requests = mutableListOf<ResponsesApiRequest>()
        val stopRequests = mutableListOf<StopRequest>()
        val storage = InMemoryKodexAgentStorage(testSettings())
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    val ordinal = requests.size
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage(if (ordinal == 1) "first" else "second")),
                        ResponsesStreamEvent.Completed(Response(id = "response_$ordinal", endTurn = true)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    stop = { request ->
                        stopRequests += request
                        if (stopRequests.size == 1) {
                            StopResult.Continue(
                                listOf(
                                    HookPromptFragment(
                                        text = "Continue <now> & finish",
                                        hookRunId = "run&\"",
                                    ),
                                ),
                            )
                        } else {
                            StopResult.Finish
                        }
                    },
                ),
            )

        runtime.appendUserMessage(listOf(ContentItem.InputText("start")))
        assertEquals(Unit, runtime.resume())

        assertEquals(2, requests.size)
        assertEquals(listOf(false, true), stopRequests.map(StopRequest::stopHookActive))
        assertEquals(listOf("first", "second"), stopRequests.map(StopRequest::lastAssistantMessage))
        assertEquals(stopRequests[0].context.turnId, stopRequests[1].context.turnId)
        val hookPromptMessage = storage.stableHistoryItems()
            .filterIsInstance<ResponseItem.Message>()
            .last { message -> message.role == MessageRole.User }
        assertEquals(
            "<hook_prompt hook_run_id=\"run&amp;&quot;\">Continue &lt;now&gt; &amp; finish</hook_prompt>",
            hookPromptMessage.inputText(),
        )

        val requestPromptMessage = requests[1].input
            .filterIsInstance<ResponseItem.Message>()
            .last { message -> message.role == MessageRole.User }
        assertEquals(hookPromptMessage, requestPromptMessage)
    }

    test("a later user turn gets a new id shared by hooks and settings") {
        val turnIds = mutableListOf<String>()
        val storage = InMemoryKodexAgentStorage(testSettings())
        var responseOrdinal = 0
        val state = KodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    responseOrdinal += 1
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage("answer $responseOrdinal")),
                        ResponsesStreamEvent.Completed(Response(id = "response_$responseOrdinal", endTurn = true)),
                    )
                }
            },
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = TestLogger,
            )
            .turnHookRuntime(
                logger = TestLogger,
                hooks = RecordingTurnHooks(
                    userPrompt = { request ->
                        turnIds += request.context.turnId
                        UserPromptSubmitResult.Continue()
                    },
                ),
            )

        runtime.markNewTurn()
        runtime.appendUserMessage(listOf(ContentItem.InputText("one")))
        assertEquals(Unit, runtime.resume())
        runtime.markNewTurn()
        runtime.appendUserMessage(listOf(ContentItem.InputText("two")))
        assertEquals(Unit, runtime.resume())

        assertEquals(2, turnIds.size)
        assertNotEquals(turnIds[0], turnIds[1])
        assertEquals(turnIds[1], storage.settings.latestValue().turnId)
    }
    }
}

private class RecordingTurnHooks(
    private val userPrompt: suspend (UserPromptSubmitRequest) -> UserPromptSubmitResult = {
        UserPromptSubmitResult.Continue()
    },
    private val stop: suspend (StopRequest) -> StopResult = { StopResult.Finish },
) : TurnHooks {
    override suspend fun onUserPromptSubmit(request: UserPromptSubmitRequest): UserPromptSubmitResult =
        userPrompt(request)

    override suspend fun onStop(request: StopRequest): StopResult = stop(request)
}

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private fun requestUserInputCall(
    arguments: String =
        """{"questions":[{"id":"scope","header":"Scope","question":"Which scope?"}]}""",
): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(
        name = "request_user_input",
        arguments = arguments,
        callId = "call_request_user_input",
    )

private fun ResponseItem.Message.inputText(): String =
    content.filterIsInstance<ContentItem.InputText>()
        .joinToString(separator = "", transform = ContentItem.InputText::text)

private suspend fun KodexAgentStorage.stableEvents(): List<StableCleanEvent> =
    stable.indexes().toList().map { index -> stable[index] }

private suspend fun KodexAgentStorage.stableHistoryItems(): List<ResponseItem.HistoryItem> =
    stable.indexes().toList().flatMap { index ->
        stable[index].toResponseHistoryItems()
    }

private fun testSettings(): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"))

private fun testModelCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
    )

private val TestLogger = KotlinLogging.logger {}
