package io.github.stream29.codex.lite.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentcontext.prefix.render.render as renderCollaborationMode
import io.github.stream29.codex.lite.agentcontext.prefix.render.renderMultiAgentMode
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstate.contract.forcedCompact
import io.github.stream29.codex.lite.agentstate.contract.renameThread
import io.github.stream29.codex.lite.agentstate.tool.toPendingToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableReasoning
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableUserMessageContent
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.nextIndex
import io.github.stream29.codex.lite.agentstorage.contract.revert
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.FailedResponse
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CallToolResult
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.CodexResponsesMetadata
import io.github.stream29.codex.lite.openai.CodexResponsesRequestKind
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.Reasoning
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ReasoningItemReasoningSummary
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseError
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.ServiceTier
import io.github.stream29.codex.lite.openai.TokenUsage
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.codexRequestWindowId
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

val codexAgentStateImplTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("state scope is an independently cancellable child of its owner") {
        val owner = supervisorChildScope()
        val state = owner.CodexAgentState(
            client = mockOpenAiClient(),
            storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model"))),
        )

        assertNotEquals(owner.coroutineContext.job, state.coroutineContext.job)
        state.cancelAndJoin()

        assertTrue(owner.coroutineContext.job.isActive)
        assertFalse(state.coroutineContext.job.isActive)
        owner.cancelAndJoin()
    }

    test("owner cancellation propagates to the state scope") {
        val owner = supervisorChildScope()
        val state = owner.CodexAgentState(
            client = mockOpenAiClient(),
            storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model"))),
        )

        owner.cancelAndJoin()

        assertFalse(state.coroutineContext.job.isActive)
    }

    test("initialization publishes storage and observable state together") {
        val storage = InMemoryCodexAgentStorage.empty()
        val state = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )
        val settings = CodexAgentSettings(OpenAiModelId("test-model"))

        assertEquals(-1, state.latestIndex.value)
        assertEquals(CodexAgentStateValue.Empty, state.state.value)

        assertEquals(
            0,
            state.modify { mutableStorage ->
                mutableStorage.initialize(settings)
                mutableStorage.latestIndex()
            },
        )

        assertEquals(0, state.latestIndex.value)
        assertEquals(CodexAgentStateValue.Empty, state.state.value)
        assertEquals(settings, storage.settings[0])
        assertEquals(0L, storage.tokenCount[0])
        assertFailsWith<IllegalArgumentException> {
            state.modify { mutableStorage -> mutableStorage.initialize(settings) }
        }
    }

    test("fork initialization reconstructs the copied state") {
        val settings = CodexAgentSettings(OpenAiModelId("test-model"))
        val source = InMemoryCodexAgentStorage(settings).apply {
            history[1] = userMessage("Question")
            history[2] = assistantMessage("Answer")
        }
        val target = InMemoryCodexAgentStorage.empty()
        val state = CodexAgentState(
            client = mockOpenAiClient(),
            storage = target,
        )

        assertEquals(
            2,
            state.modify { mutableStorage ->
                source.forkTo(3, mutableStorage)
                mutableStorage.latestIndex()
            },
        )

        assertEquals(2, state.latestIndex.value)
        assertEquals(CodexAgentStateValue.AssistantMessage, state.state.value)
        assertEquals(userMessage("Question"), target.history[1])
        assertEquals(assistantMessage("Answer"), target.history[2])
    }

    test("modify refreshes observable state after a failed storage write") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val state = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertFailsWith<IllegalStateException> {
            state.modify { mutableStorage ->
                mutableStorage.history[1] = userMessage("Persisted before failure.")
                error("Stop after the write.")
            }
        }

        assertEquals(1, state.latestIndex.value)
        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
        assertEquals(userMessage("Persisted before failure."), storage.history[1])
    }

    test("request projection maps Codex ultra reasoning to Responses max") {
        val metadata = CodexResponsesMetadata(
            threadId = "thread_1",
            turnId = "turn_1",
            windowId = "window_1",
            requestKind = CodexResponsesRequestKind.Turn,
        )
        val request = CodexAgentSettings(
            model = OpenAiModelId("test-model"),
            reasoning = Reasoning(effort = ReasoningEffort.Ultra),
        ).toResponsesApiRequest(
            input = emptyList(),
            clientMetadata = metadata.toCodexClientMetadata(),
            tools = emptyList(),
        )

        assertEquals(ReasoningEffort.Max, request.reasoning.effort)
    }

    test("request projection preserves the selected service tier") {
        val metadata = CodexResponsesMetadata(
            threadId = "thread_1",
            turnId = "turn_1",
            windowId = "window_1",
            requestKind = CodexResponsesRequestKind.Turn,
        )
        val request = CodexAgentSettings(
            model = OpenAiModelId("test-model"),
            serviceTier = ServiceTier.Fast,
        ).toResponsesApiRequest(
            input = emptyList(),
            clientMetadata = metadata.toCodexClientMetadata(),
            tools = emptyList(),
        )

        assertEquals(ServiceTier.Fast, request.serviceTier)
    }

    test("request projection converts MCP results to function call outputs") {
        val metadata = CodexResponsesMetadata(
            threadId = "thread_1",
            turnId = "turn_1",
            windowId = "window_1",
            requestKind = CodexResponsesRequestKind.Turn,
        )
        val output = ResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = CallToolResult(
                content = listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "result")
                    },
                ),
            ),
        )
        val request = CodexAgentSettings(
            model = OpenAiModelId("test-model"),
        ).toResponsesApiRequest(
            input = listOf(output),
            clientMetadata = metadata.toCodexClientMetadata(),
            tools = emptyList(),
        )

        assertEquals(
            ResponseItem.FunctionCallOutput(
                callId = output.callId,
                output = output.output.toFunctionCallOutputPayload(OpenAiJsonCodec),
            ),
            request.input.single(),
        )
    }

    test("append user message allows consecutive user messages") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )
        val turnId = storage.settings[0].turnId
        val context = userMessage("# AGENTS.md instructions")
        val userInput = userMessage("Implement the change.")

        assertEquals(1, agent.appendUserMessage(context.content))
        assertEquals(2, agent.appendUserMessage(userInput.content))

        assertEquals(context, storage.history[1])
        assertEquals(userInput, storage.history[2])
        assertEquals(listOf(1, 2), storage.stable.indexes().toList())
        assertEquals(
            StableUserMessage(
                listOf(StableUserMessageContent.Text("# AGENTS.md instructions")),
            ),
            storage.stable[1],
        )
        assertEquals(
            StableUserMessage(
                listOf(StableUserMessageContent.Text("Implement the change.")),
            ),
            storage.stable[2],
        )
        assertEquals(turnId, storage.settings[2].turnId)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
    }

    test("mark new turn rotates turn id independently from user messages") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )
        val initialTurnId = storage.settings[0].turnId

        assertEquals(0, agent.markNewTurn())
        assertEquals(initialTurnId, storage.settings[0].turnId)
        assertEquals(1, agent.appendUserMessage(userMessage("First turn.").content))
        assertEquals(initialTurnId, storage.settings[1].turnId)

        assertEquals(2, agent.markNewTurn())
        val nextTurnId = storage.settings[2].turnId
        assertNotEquals(initialTurnId, nextTurnId)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)

        assertEquals(3, agent.appendUserMessage(userMessage("Second turn.").content))
        assertEquals(nextTurnId, storage.settings[3].turnId)
    }

    test("agent messages are requestable user-side history") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )
        val task = ResponseItem.AgentMessage(
            author = "/root",
            recipient = "/root/worker",
            content = listOf(
                AgentMessageInputContent.InputText(
                    "Message Type: NEW_TASK\nTask name: /root/worker\nSender: /root\nPayload:\nInspect storage.",
                ),
            ),
        )

        assertEquals(1, agent.injectHistory(listOf(task)))
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(
            StableAgentMessage(
                author = task.author,
                recipient = task.recipient,
                content = task.content,
            ),
            storage.stable[1],
        )

        val reloaded = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )
        assertEquals(CodexAgentStateValue.UserMessage, reloaded.state.value)
    }

    test("history injection projects stable events and pending tool snapshots") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )
        val developerContent = listOf(ContentItem.InputText("Injected host context."))
        val reasoning = ResponseItem.Reasoning(
            summary = listOf(
                ReasoningItemReasoningSummary.SummaryText("Inspecting"),
            ),
        )
        val assistant = assistantMessage("Prior answer.")
        val call = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = """{"cmd":"date"}""",
            callId = "call_1",
        )

        assertEquals(
            4,
            agent.injectHistory(
                listOf(
                    ResponseItem.Message(
                        role = MessageRole.Developer,
                        content = developerContent,
                    ),
                    reasoning,
                    assistant,
                    call,
                ),
            ),
        )

        assertEquals(listOf(1, 2, 3), storage.stable.indexes().toList())
        assertEquals(StableDeveloperMessage(developerContent), storage.stable[1])
        assertEquals(StableReasoning("Inspecting"), storage.stable[2])
        assertEquals(StableAssistantMessage("Prior answer."), storage.stable[3])
        assertEquals(listOf(call.toPendingToolEvent()), storage.unstable[4])
        assertEquals(CodexAgentStateValue.ToolPending(listOf(call)), agent.state.value)
    }

    test("history injection removes completed calls from the pending snapshot") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )
        val call = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = """{"cmd":"date"}""",
            callId = "call_1",
        )
        val output = ResponseItem.FunctionCallOutput(
            callId = call.callId,
            output = FunctionCallOutputPayload.fromText("done"),
        )

        assertEquals(2, agent.injectHistory(listOf(call, output)))

        assertEquals(listOf(1, 2), storage.unstable.indexes().toList())
        assertEquals(listOf(call.toPendingToolEvent()), storage.unstable[1])
        assertEquals(emptyList(), storage.unstable[2])
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    test("user messages do not derive or replace the thread name") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(OpenAiModelId("test-model"), threadName = "Session 0"),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        agent.appendUserMessage(listOf(ContentItem.InputImage("data:image/png;base64,AA==")))
        assertEquals("Session 0", storage.settings[1].threadName)

        agent.appendUserMessage(
            listOf(ContentItem.InputText("<environment_context/>\n## My request for Codex:  Initial request  ")),
        )
        assertEquals("Session 0", storage.settings[2].threadName)

        agent.renameThread("Named by user")
        agent.appendUserMessage(listOf(ContentItem.InputText("Later request")))
        assertEquals("Named by user", storage.settings[4].threadName)

        storage.revert(untilExclusive = 1)
        assertEquals("Session 0", storage.settings[0].threadName)
    }

    test("revert restores a completed assistant snapshot across every timeline") {
        val initialSettings = CodexAgentSettings(OpenAiModelId("initial-model"))
        val storage = InMemoryCodexAgentStorage(initialSettings)
        val initialCheckpoint = storage.compaction[0]
        val user = userMessage("First turn.")
        val assistant = assistantMessage("First answer.")
        val replacementSettings = CodexAgentSettings(OpenAiModelId("replacement-model"))

        storage.history[1] = user
        storage.timestamp[1] = instant(1)
        storage.history[2] = assistant
        storage.timestamp[2] = instant(2)
        storage.tokenCount[2] = 20
        storage.settings[3] = replacementSettings
        storage.compaction[4] = initialCheckpoint.copy(
            prefix = listOf(user, assistant),
            historyBaseIndex = 3,
            windowNumber = 1,
            previousWindowId = initialCheckpoint.windowId,
            windowId = "window-1",
        )
        storage.timestamp[5] = instant(5)
        storage.tokenCount[5] = 50

        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(5, agent.latestIndex.value)
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)

        assertEquals(
            2,
            agent.modify { mutableStorage ->
                mutableStorage.revert(untilExclusive = 3)
                mutableStorage.latestIndex()
            },
        )

        assertEquals(2, agent.latestIndex.value)
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertEquals(assistant, storage.history[2])
        assertEquals(initialSettings, storage.settings[2])
        assertEquals(initialCheckpoint, storage.compaction[2])
        assertEquals(20, storage.tokenCount[2])
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(null, storage.settings.nextIndex(2))
        assertEquals(null, storage.compaction.nextIndex(2))
        assertEquals(null, storage.timestamp.nextIndex(2))
        assertEquals(null, storage.tokenCount.nextIndex(2))
    }

    test("modify publishes the storage state selected by revert") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        storage.history[1] = userMessage("First turn.")
        storage.history[2] = assistantMessage("First answer.")
        storage.history[3] = userMessage("Second turn.")
        storage.history[4] = assistantMessage("Second answer.")
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        agent.modify { mutableStorage ->
            mutableStorage.revert(untilExclusive = 2)
        }

        assertEquals(1, agent.latestIndex.value)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(userMessage("First turn."), storage.history[1])
        assertEquals(null, storage.history.nextIndex(1))
    }

    test("state tracks pending tool calls and rejects mismatched results") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val user = userMessage("Run a command.")
        val call = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{}",
            callId = "call_1",
        )
        storage.history[1] = user
        storage.history[2] = call
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.ToolPending(listOf(call)), agent.state.value)

        assertFailsWith<IllegalArgumentException> {
            agent.completeToolCall(
                ResponseItem.FunctionCallOutput(
                    callId = "other_call",
                    output = FunctionCallOutputPayload.fromText("no"),
                ),
                testCompletedToolEvent("exec_command", "no"),
            )
        }
        assertFailsWith<CodexAgentStateInvalidTransitionException> {
            agent.requestResponseApi().toList()
        }

        agent.completeToolCall(
            ResponseItem.FunctionCallOutput(
                callId = "call_1",
                output = FunctionCallOutputPayload.fromText("done"),
            ),
            testCompletedToolEvent("exec_command", "done"),
        )
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    test("complete tool call persists results one at a time") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val firstCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{}",
            callId = "call_1",
        )
        val secondCall = ResponseItem.CustomToolCall(
            name = "apply_patch",
            input = "*** Begin Patch",
            callId = "call_2",
        )
        storage.history[1] = userMessage("Run both tools.")
        storage.history[2] = firstCall
        storage.history[3] = secondCall
        val initialPending = listOf(
            firstCall.toPendingToolEvent(),
            secondCall.toPendingToolEvent(),
        )
        storage.unstable[3] = initialPending
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.ToolPending(listOf(firstCall, secondCall)), agent.state.value)

        val secondOutput = ResponseItem.CustomToolCallOutput(
            callId = secondCall.callId,
            name = secondCall.name,
            output = FunctionCallOutputPayload.fromText("second"),
        )
        val firstOutput = ResponseItem.FunctionCallOutput(
            callId = firstCall.callId,
            output = FunctionCallOutputPayload.fromText("first"),
        )
        val secondCompleted = testCompletedToolEvent(secondCall.name, "second")
        val firstCompleted = testCompletedToolEvent(firstCall.name, "first")

        val secondIndex = agent.completeToolCall(secondOutput, secondCompleted)

        assertEquals(4, secondIndex)
        assertEquals(secondOutput, storage.history[4])
        assertEquals(secondCompleted, storage.stable[4])
        assertEquals(listOf(initialPending.first()), storage.unstable[4])
        assertEquals(CodexAgentStateValue.ToolPending(listOf(firstCall)), agent.state.value)

        val finalIndex = agent.completeToolCall(firstOutput, firstCompleted)

        assertEquals(5, finalIndex)
        assertEquals(secondOutput, storage.history[4])
        assertEquals(firstOutput, storage.history[5])
        assertEquals(firstCompleted, storage.stable[5])
        assertEquals(emptyList(), storage.unstable[5])
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    test("state completes a client tool search call through the generic tool-call path") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val functionCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{}",
            callId = "call_function",
        )
        val toolSearchCall = ResponseItem.ClientToolSearchCall(
            callId = "call_search",
            arguments = buildJsonObject { put("query", "calendar") },
        )
        storage.history[1] = userMessage("Find and run a tool.")
        storage.history[2] = functionCall
        storage.history[3] = toolSearchCall
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(
            CodexAgentStateValue.ToolPending(listOf(functionCall, toolSearchCall)),
            agent.state.value,
        )

        assertFailsWith<IllegalArgumentException> {
            agent.completeToolCall(
                ResponseItem.ClientToolSearchOutput(
                    callId = "other_call",
                    status = "completed",
                    tools = emptyList(),
                ),
                testCompletedToolEvent("tool_search", "no"),
            )
        }

        val searchOutput = ResponseItem.ClientToolSearchOutput(
            callId = toolSearchCall.callId,
            status = "completed",
            tools = emptyList(),
        )
        assertEquals(
            4,
            agent.completeToolCall(
                searchOutput,
                testCompletedToolEvent("tool_search", "completed"),
            ),
        )
        assertEquals(searchOutput, storage.history[4])
        assertEquals(CodexAgentStateValue.ToolPending(listOf(functionCall)), agent.state.value)

        agent.completeToolCall(
            ResponseItem.FunctionCallOutput(
                callId = functionCall.callId,
                output = FunctionCallOutputPayload.fromText("done"),
            ),
            testCompletedToolEvent(functionCall.name, "done"),
        )
        val reloadedAgent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.ToolCompleted, reloadedAgent.state.value)
    }

    test("state does not locally pend hosted tool-search history") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        storage.history[1] = userMessage("Search server tools.")
        storage.history[2] = ResponseItem.ServerToolSearchCall(
            arguments = buildJsonObject { put("query", "calendar") },
        )
        storage.history[3] = ResponseItem.ServerToolSearchOutput(
            status = "completed",
            tools = emptyList(),
        )

        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
    }

    test("state reconstruction pairs tail tool calls before choosing state") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val firstCall = ResponseItem.FunctionCall(
            name = "first",
            arguments = "{}",
            callId = "call_1",
        )
        val secondCall = ResponseItem.CustomToolCall(
            name = "second",
            input = "input",
            callId = "call_2",
        )
        storage.history[1] = userMessage("Run both tools.")
        storage.history[2] = firstCall
        storage.history[3] = secondCall
        storage.history[4] = ResponseItem.CustomToolCallOutput(
            callId = secondCall.callId,
            output = FunctionCallOutputPayload.fromText("second"),
        )
        storage.history[5] = ResponseItem.FunctionCallOutput(
            callId = firstCall.callId,
            output = FunctionCallOutputPayload.fromText("first"),
        )

        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    test("complete tool call does not update plan") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val planCall = ResponseItem.FunctionCall(
            name = "update_plan",
            arguments = "{}",
            callId = "plan_call",
        )
        storage.history[1] = userMessage("Start.")
        storage.history[2] = planCall
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        val outputIndex = agent.completeToolCall(
            ResponseItem.FunctionCallOutput(
                callId = planCall.callId,
                output = FunctionCallOutputPayload.fromText("Plan updated").copy(success = true),
            ),
            testCompletedToolEvent(planCall.name, "Plan updated"),
        )

        assertEquals(3, outputIndex)
        assertEquals(null, storage.settings.nextIndex(3))
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    test("default settings allocate uuid v7 turn id") {
        val settings = CodexAgentSettings(OpenAiModelId("test-model"))

        assertEquals('7', settings.turnId[14])
        assertEquals(null, settings.autoCompactionTokenLimit)
        assertEquals(null, settings.installationId)
        assertEquals(null, settings.sessionId)
    }

    test("initialized storage loads as an empty agent state") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertEquals(0, agent.latestIndex.value)
        assertEquals(CodexAgentStateValue.Empty, agent.state.value)
    }

    test("response failure is published without agent state exception") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val failure = ResponsesStreamEvent.Failed(
            FailedResponse(ResponseError(message = "bad request")),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { flowOf<ResponsesStreamEvent>(failure) }
            },
            storage = storage,
        )
        val received = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start."))

        agent.requestResponseApi().collect(received::add)

        assertEquals(listOf<ResponsesStreamEvent>(failure), received)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, storage.latestIndex())
    }

    test("resume executes one request when end turn is false") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    when (requests.size) {
                        1 -> flow {
                            emit(
                                ResponsesStreamEvent.OutputItemDone(
                                    outputIndex = 0,
                                    item = ResponseItem.Reasoning(summary = emptyList()),
                                ),
                            )
                            emit(
                                ResponsesStreamEvent.OutputItemDone(
                                    outputIndex = 1,
                                    item = assistantMessage("Preparing the answer."),
                                ),
                            )
                            emit(
                                ResponsesStreamEvent.Completed(
                                    Response(
                                        id = "response_1",
                                        usage = TokenUsage(
                                            inputTokens = 10,
                                            outputTokens = 2,
                                            totalTokens = 12,
                                        ),
                                        endTurn = false,
                                    ),
                                ),
                            )
                        }

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = assistantMessage("Done."),
                            ),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_2",
                                    usage = TokenUsage(
                                        inputTokens = 12,
                                        outputTokens = 1,
                                        totalTokens = 13,
                                    ),
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

        val user = userMessage("Answer briefly.")
        assertEquals(1, agent.appendUserMessage(user, tokenCount = 1))

        agent.requestResponseApi().toList()

        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertEquals(1, requests.size)
        assertRequestInput(requests[0].input, user)
        assertEquals(4, storage.latestIndex())
        assertIs<ResponseItem.Reasoning>(storage.history[2])
        assertEquals(assistantMessage("Preparing the answer."), storage.history[3])
        assertEquals(listOf(1, 2, 3), storage.stable.indexes().toList())
        assertEquals(StableReasoning(""), storage.stable[2])
        assertEquals(StableAssistantMessage("Preparing the answer."), storage.stable[3])
        assertEquals(null, storage.history.nextIndex(4))
        assertEquals(12, storage.tokenCount[4])
        assertTrue(storage.timestamp[4] > instant(0))
        assertEquals(4, agent.latestIndex.value)
    }

    test("resume sends stored reasoning items back to model") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val user = userMessage("Continue.")
        val reasoning = ResponseItem.Reasoning(summary = emptyList())
        val assistant = assistantMessage("Ready.")
        storage.history[1] = user
        storage.history[2] = reasoning
        storage.history[3] = assistant
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.Completed(
                            Response(id = "response_1", endTurn = true),
                        ),
                    )
                }
            },
            storage = storage,
        )

        agent.requestResponseApi().toList()

        assertRequestInput(requests.single().input, user, reasoning, assistant)
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
    }

    test("resume publishes output item and raw stream events before completed") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val outputItem = assistantMessage("Streaming item is visible before completion.")
        val outputEvent = ResponsesStreamEvent.OutputItemDone(
            outputIndex = 0,
            item = outputItem,
        )
        val completedEvent = ResponsesStreamEvent.Completed(
            Response(
                id = "response_1",
                usage = TokenUsage(
                    inputTokens = 8,
                    outputTokens = 1,
                    totalTokens = 9,
                ),
                endTurn = true,
            ),
        )
        val outputItemCollected = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        emit(outputEvent)
                        outputItemCollected.complete(Unit)
                        releaseCompletion.await()
                        emit(completedEvent)
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Start streaming."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().toList()
        }

        outputItemCollected.await()
        assertEquals(2, storage.latestIndex())
        assertEquals(outputItem, storage.history[2])
        assertEquals(2, agent.latestIndex.value)
        assertEquals(-1, storage.tokenCount.latestIndex())

        releaseCompletion.complete(Unit)
        assertEquals(listOf(outputEvent, completedEvent), runningResume.await())
        assertEquals(3, storage.latestIndex())
        assertEquals(null, storage.history.nextIndex(3))
        assertEquals(9, storage.tokenCount[3])
    }

    test("request state replays each active message output independently") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val item = ResponseItem.Message(
            id = ResponseItemId("message_1"),
            role = MessageRole.Assistant,
            content = emptyList(),
        )
        val added = ResponsesStreamEvent.OutputItemAdded(outputIndex = 0, item = item)
        val delta = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "hello",
        )
        val done = ResponsesStreamEvent.OutputItemDone(
            outputIndex = 0,
            item = item.copy(content = listOf(ContentItem.OutputText("hello"))),
        )
        val release = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        emit(added)
                        emit(delta)
                        release.await()
                        emit(done)
                        emit(ResponsesStreamEvent.Completed(Response(id = "response_1")))
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Stream a message."))
        val request = async { agent.requestResponseApi().toList() }
        val active = agent.state.first { it is CodexAgentStateValue.RequestResponse.Message }
        val output = assertIs<CodexAgentStateValue.RequestResponse.Message>(active)
        assertEquals(listOf(added, delta), output.events.replayCache)

        release.complete(Unit)
        request.await()

        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertEquals(listOf(added, delta, done), output.events.replayCache)
        assertEquals(StableAssistantMessage("hello"), storage.stable[2])
    }

    test("request state exposes agent messages separately") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val item = ResponseItem.AgentMessage(
            id = ResponseItemId("agent_message_1"),
            author = "/root",
            recipient = "/root/worker",
            content = listOf(AgentMessageInputContent.InputText("Inspect storage.")),
        )
        val added = ResponsesStreamEvent.OutputItemAdded(outputIndex = 0, item = item)
        val release = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        emit(added)
                        release.await()
                        emit(ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = item))
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Delegate the inspection."))
        val request = async { agent.requestResponseApi().toList() }
        val active = agent.state.first { it is CodexAgentStateValue.RequestResponse.AgentMessage }
        val output = assertIs<CodexAgentStateValue.RequestResponse.AgentMessage>(active)
        assertEquals(listOf(added), output.events.replayCache)

        release.complete(Unit)
        request.await()

        assertEquals(
            StableAgentMessage(
                author = item.author,
                recipient = item.recipient,
                content = item.content,
            ),
            storage.stable[2],
        )
    }

    test("request state aggregates hosted tool calls") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val item = ResponseItem.WebSearchCall(status = "in_progress")
        val added = ResponsesStreamEvent.OutputItemAdded(outputIndex = 0, item = item)
        val release = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        emit(added)
                        release.await()
                        emit(ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = item))
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Search for documentation."))
        val request = async { agent.requestResponseApi().toList() }
        val active = agent.state.first { it is CodexAgentStateValue.RequestResponse.ToolCall }
        val output = assertIs<CodexAgentStateValue.RequestResponse.ToolCall>(active)
        assertEquals(listOf(added), output.events.replayCache)

        release.complete(Unit)
        request.await()
    }

    test("resume does not wait for slow stream event collector") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val deltaEvent = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "x",
        )
        val completedEvent = ResponsesStreamEvent.Completed(
            Response(
                id = "response_1",
                usage = TokenUsage(
                    inputTokens = 8,
                    outputTokens = 1,
                    totalTokens = 9,
                ),
                endTurn = true,
            ),
        )
        val productionCompleted = CompletableDeferred<Unit>()
        val firstEventCollected = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        repeat(1_024) {
                            emit(deltaEvent)
                        }
                        emit(completedEvent)
                        productionCompleted.complete(Unit)
                    }
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start streaming."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().collect { event ->
                collected += event
                if (collected.size == 1) {
                    firstEventCollected.complete(Unit)
                    releaseCollector.await()
                }
            }
        }

        firstEventCollected.await()
        productionCompleted.await()
        agent.state.first { it !is CodexAgentStateValue.RequestResponse }
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(9, storage.tokenCount[2])

        releaseCollector.complete(Unit)
        runningResume.await()
        assertEquals(1_025, collected.size)
        assertEquals(completedEvent, collected.last())
    }

    test("cancelling resume after delta resets state and does not persist partial text") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val deltaEvent = ResponsesStreamEvent.OutputTextDelta(
            itemId = "message_1",
            outputIndex = 0,
            contentIndex = 0,
            delta = "partial",
        )
        val deltaCollected = CompletableDeferred<Unit>()
        val releaseStream = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        emit(deltaEvent)
                        releaseStream.await()
                    }
                }
            },
            storage = storage,
        )
        val user = userMessage("Start streaming.")
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(user)
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().collect { event ->
                collected += event
                deltaCollected.complete(Unit)
            }
        }

        deltaCollected.await()
        assertEquals(listOf<ResponsesStreamEvent>(deltaEvent), collected)

        runningResume.cancel(CancellationException("runtime interrupted stream"))
        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, storage.latestIndex())
        assertEquals(user, storage.history[1])
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(-1, storage.tokenCount.latestIndex())
    }

    test("resume propagates cancellation exception without wrapping it") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        throw CancellationException("transport cancelled")
                    }
                }
            },
            storage = storage,
        )
        val user = userMessage("Start streaming.")

        agent.appendUserMessage(user)
        val exception = assertFailsWith<CancellationException> {
            agent.requestResponseApi().toList()
        }

        assertEquals("transport cancelled", exception.message)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, storage.latestIndex())
        assertEquals(user, storage.history[1])
    }

    test("cancelling resume from output item collector keeps stable history item") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val outputItem = assistantMessage("Stable output.")
        val outputEvent = ResponsesStreamEvent.OutputItemDone(
            outputIndex = 0,
            item = outputItem,
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(outputEvent)
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().collect { event ->
                collected += event
                currentCoroutineContext().cancel(CancellationException("runtime cancelled after stable item"))
            }
        }

        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(listOf<ResponsesStreamEvent>(outputEvent), collected)
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(outputItem, storage.history[2])
        assertEquals(null, storage.history.nextIndex(3))
    }

    test("cancelling resume from completed collector keeps stable token count") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val completedEvent = ResponsesStreamEvent.Completed(
            Response(
                id = "response_1",
                usage = TokenUsage(
                    inputTokens = 8,
                    outputTokens = 1,
                    totalTokens = 9,
                ),
                endTurn = true,
            ),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(completedEvent)
                }
            },
            storage = storage,
        )
        val collected = mutableListOf<ResponsesStreamEvent>()

        agent.appendUserMessage(userMessage("Start."))
        val runningResume = async(start = CoroutineStart.UNDISPATCHED) {
            agent.requestResponseApi().collect { event ->
                collected += event
                currentCoroutineContext().cancel(CancellationException("runtime cancelled after completion"))
            }
        }

        assertFailsWith<CancellationException> {
            runningResume.await()
        }

        assertEquals(listOf<ResponsesStreamEvent>(completedEvent), collected)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(2, storage.latestIndex())
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(9, storage.tokenCount[2])
    }

    test("resume persists tool call output item") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val toolCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = """{"cmd":"date"}""",
            callId = "call_1",
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = toolCall,
                        ),
                        ResponsesStreamEvent.Completed(
                            Response(id = "response_1", endTurn = true),
                        ),
                    )
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("What time is it?"))
        agent.requestResponseApi().toList()

        assertEquals(1, requests.size)
        assertEquals(2, storage.latestIndex())
        assertEquals(toolCall, storage.history[2])
        assertEquals(-1, storage.tokenCount.latestIndex())
    }

    test("update settings publishes state index without history item") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("old-model")))
        val requests = mutableListOf<RecordedCreateResponse>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request, installationId, turnMetadata, windowId ->
                    requests += RecordedCreateResponse(request, installationId, turnMetadata, windowId)
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = assistantMessage("Using the updated model."),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )
        val user = userMessage("Use the new settings.")

        agent.appendUserMessage(user, tokenCount = 1)
        val settingsIndex = agent.updateSettings(
            CodexAgentSettings(
                model = OpenAiModelId("new-model"),
                installationId = "install",
                sessionId = "session",
            ),
        )

        assertEquals(2, settingsIndex)
        assertEquals(null, storage.history.nextIndex(2))
        assertEquals(OpenAiModelId("new-model"), storage.settings[2].model)
        assertEquals(1, storage.tokenCount[2])

        agent.requestResponseApi().toList()

        val request = requests.single()
        assertEquals(OpenAiModelId("new-model"), request.request.model)
        assertRequestInput(request.request.input, user)
        assertEquals(false, request.request.store)
        assertEquals("install", request.installationId)
        val clientMetadata = assertNotNull(request.request.clientMetadata)
        assertEquals("install", clientMetadata.installationId)
        assertEquals("session", clientMetadata.sessionId)
        assertEquals(storage.id.toCodexThreadId(), clientMetadata.threadId)
        assertEquals(storage.settings[2].turnId, clientMetadata.turnId)
        assertEquals("${storage.id.toCodexThreadId()}:0", request.windowId)
        assertEquals(request.turnMetadata, clientMetadata.turnMetadata)
        assertTrue(request.turnMetadata.contains("\"request_kind\":\"turn\""))
    }

    test("append plan update publishes plan and matching tool result atomically") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val plan = UpdatePlanArgs(
            plan = listOf(PlanItemArg(step = "inspect", status = StepStatus.InProgress)),
        )
        val planCall = ResponseItem.FunctionCall(
            name = "update_plan",
            arguments = "{}",
            callId = "plan_call",
        )
        storage.history[1] = userMessage("Start.")
        storage.tokenCount[1] = 1
        storage.history[2] = planCall
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        val output = ResponseItem.FunctionCallOutput(
            callId = planCall.callId,
            output = FunctionCallOutputPayload.fromText("Plan updated").copy(success = true),
        )
        val planIndex = agent.appendPlanUpdate(
            output = output,
            plan = plan,
        )

        assertEquals(3, planIndex)
        assertEquals(plan, storage.settings[3].plan)
        assertEquals(planCall, storage.history[2])
        assertEquals(output, storage.history[3])
        assertEquals(1, storage.tokenCount[3])
        assertEquals(CodexAgentStateValue.ToolCompleted, agent.state.value)
    }

    test("append plan update rejects plan mode") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                collaborationMode = ModeKind.Plan,
            ),
        )
        val planCall = ResponseItem.FunctionCall(
            name = "update_plan",
            arguments = "{}",
            callId = "plan_call",
        )
        storage.history[1] = userMessage("Start.")
        storage.history[2] = planCall
        val agent = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
        )

        assertFailsWith<IllegalArgumentException> {
            agent.appendPlanUpdate(
                output = ResponseItem.FunctionCallOutput(
                    callId = planCall.callId,
                    output = FunctionCallOutputPayload.fromText("Plan updated"),
                ),
                plan = UpdatePlanArgs(plan = emptyList()),
            )
        }

        assertEquals(2, storage.latestIndex())
        assertEquals(CodexAgentStateValue.ToolPending(listOf(planCall)), agent.state.value)
    }

    test("mutation fails when another mutation is running") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flow {
                        requestStarted.complete(Unit)
                        releaseResponse.await()
                        emit(ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)))
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Wait."))
        val runningResume = async { agent.requestResponseApi().toList() }
        requestStarted.await()

        val exception = assertFailsWith<CodexAgentStateInvalidTransitionException> {
            agent.appendUserMessage(userMessage("Concurrent input."))
        }
        assertIs<CodexAgentStateValue.RequestResponse>(exception.currentState)

        releaseResponse.complete(Unit)
        runningResume.await()
    }

    test("resume persists compaction output without requesting compaction") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val compactItem = ResponseItem.ContextCompaction(encryptedContent = "compact")
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            outputIndex = 0,
                            item = compactItem,
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )

        val user = userMessage("Compact.")
        agent.appendUserMessage(user)
        agent.requestResponseApi().toList()

        assertEquals(2, storage.latestIndex())
        assertEquals(compactItem, storage.history[2])
        assertEquals(0, storage.compaction[2].historyBaseIndex)
    }

    test("forced compact uses remote compaction v2 without rotating the turn") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val initialTurnId = storage.settings[0].turnId
        val initialCheckpoint = storage.compaction[0]
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(
                            inputTokens = 10,
                            outputTokens = 1,
                            totalTokens = 11,
                        ),
                    )
                }
            },
            storage = storage,
        )

        val user = userMessage("This context is too large.")
        agent.appendUserMessage(user)
        val compactIndex = agent.forcedCompact()

        assertEquals(2, compactIndex)
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
        assertEquals(1, compactRequests.size)
        val compactRequest = compactRequests.single()
        assertEquals(listOf(user, ResponseItem.CompactionTrigger), compactRequest.request.input)
        assertEquals(null, compactRequest.installationId)
        val clientMetadata = assertNotNull(compactRequest.request.clientMetadata)
        assertEquals(null, clientMetadata.sessionId)
        assertEquals(storage.id.toCodexThreadId(), clientMetadata.threadId)
        assertEquals("${storage.id.toCodexThreadId()}:0", compactRequest.windowId)
        assertEquals(initialTurnId, clientMetadata.turnId)
        assertEquals(compactRequest.turnMetadata, clientMetadata.turnMetadata)
        assertEquals(initialCheckpoint.codexRequestWindowId(storage.id.toCodexThreadId()), compactRequest.windowId)
        assertTrue(compactRequest.turnMetadata.contains("\"request_kind\":\"compaction\""))
        assertTrue(compactRequest.turnMetadata.contains("\"trigger\":\"manual\""))
        assertTrue(compactRequest.turnMetadata.contains("\"reason\":\"user_requested\""))
        assertTrue(compactRequest.turnMetadata.contains("\"phase\":\"standalone_turn\""))
        assertEquals(ResponseItem.ContextCompaction(encryptedContent = "compact"), storage.history[2])
        assertEquals(StableContextCompaction, storage.stable[2])
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(user, compaction),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
        assertEquals(11, storage.tokenCount[2])
        assertEquals(initialTurnId, storage.settings[2].turnId)
        assertEquals(2, agent.latestIndex.value)
    }

    test("remote compaction preserves deferred tool schemas in its request") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val user = userMessage("Search for the available image tool.")
        val searchOutput = ResponseItem.ClientToolSearchOutput(
            callId = "call_search",
            status = "completed",
            tools = listOf(
                ResponsesApiNamespace(
                    name = "mcp__images",
                    description = "Image tools",
                    tools = emptyList(),
                ),
            ),
        )
        storage.history[1] = user
        storage.history[2] = searchOutput
        val compactionRequests = mutableListOf<ResponsesApiRequest>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, _, _, _ ->
                    compactionRequests += request
                    remoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compact"))
                }
            },
            storage = storage,
        )

        agent.forcedCompact()

        assertEquals(
            listOf(user, searchOutput, ResponseItem.CompactionTrigger),
            compactionRequests.single().input,
        )
        assertEquals(searchOutput, storage.history[2])
    }

    test("remote compaction v2 retains only newest user messages within rust budget") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val old = userMessage("old user message")
        val oversized = userMessage("x".repeat((64_000 + 2) * 4))
        val newest = userMessage("newest user message")
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        storage.history[1] = old
        storage.history[2] = oversized
        storage.history[3] = newest
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { _, _, _, _ ->
                    remoteCompactionV2Response(compaction)
                }
            },
            storage = storage,
        )

        agent.forcedCompact()

        val prefix = storage.compaction[4].prefix
        assertEquals(3, prefix.size)
        val truncated = assertIs<ResponseItem.Message>(prefix[0])
        val retainedText = assertIs<ContentItem.InputText>(truncated.content.single()).text
        assertTrue(retainedText.contains("tokens truncated"))
        assertEquals(newest, prefix[1])
        assertEquals(compaction, prefix[2])
        assertTrue(prefix.none { it == old })
    }

    test("remote compaction v2 uses storage thread identity") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                installationId = "install",
                sessionId = "session",
            ),
        )
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
                    remoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compact"))
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Compact."))
        agent.forcedCompact()

        val compactRequest = compactRequests.single()
        assertEquals("install", compactRequest.installationId)
        val clientMetadata = assertNotNull(compactRequest.request.clientMetadata)
        assertEquals("session", clientMetadata.sessionId)
        assertEquals(storage.id.toCodexThreadId(), clientMetadata.threadId)
    }

    test("remote compaction v2 uses window number from checkpoint") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val initialCheckpoint = CompactionCheckpoint(
            prefix = emptyList(),
            historyBaseIndex = 0,
            windowNumber = 7,
            firstWindowId = "window-7",
            windowId = "window-7",
        )
        val user = userMessage("Compact.")
        storage.compaction[1] = initialCheckpoint
        storage.history[1] = user
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
                    remoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compact"))
                }
            },
            storage = storage,
        )

        agent.forcedCompact()

        val compactRequest = compactRequests.single()
        assertEquals("${storage.id.toCodexThreadId()}:7", compactRequest.windowId)
        assertAdvancedCompactionCheckpoint(
            checkpoint = storage.compaction[2],
            prefix = listOf(user, ResponseItem.Compaction(encryptedContent = "compact")),
            historyBaseIndex = 3,
            previousCheckpoint = initialCheckpoint,
        )
    }

    test("resume does not apply auto compaction policy") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 90,
            ),
        )
        val initialCheckpoint = storage.compaction[0]
        val user = userMessage("Keep this user message.")
        val compaction = ResponseItem.Compaction(encryptedContent = "pre-turn-compact")
        val final = assistantMessage("After pre-turn compact.")
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val responseRequests = mutableListOf<RecordedCreateResponse>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(90, 1, 91),
                    )
                }

                createResponse { request, installationId, turnMetadata, windowId ->
                    responseRequests += RecordedCreateResponse(request, installationId, turnMetadata, windowId)
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, final),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(user, tokenCount = 90)
        agent.requestResponseApi().toList()

        assertEquals(0, compactRequests.size)
        assertEquals(1, responseRequests.size)
        assertRequestInput(responseRequests.single().request.input, user)
        assertEquals("${storage.id.toCodexThreadId()}:0", responseRequests.single().windowId)
        assertTrue(responseRequests.single().turnMetadata.contains("\"request_kind\":\"turn\""))
        assertEquals(final, storage.history[2])
        assertEquals(initialCheckpoint, storage.compaction[2])
    }

    test("resume does not continue or compact when follow up is needed") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 20,
            ),
        )
        val initialCheckpoint = storage.compaction[0]
        val user = userMessage("Continue until final.")
        val partial = assistantMessage("Partial answer.")
        val compaction = ResponseItem.Compaction(encryptedContent = "mid-turn-compact")
        val final = assistantMessage("Final answer.")
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val responseRequests = mutableListOf<RecordedCreateResponse>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(20, 1, 21),
                    )
                }

                createResponse { request, installationId, turnMetadata, windowId ->
                    responseRequests += RecordedCreateResponse(request, installationId, turnMetadata, windowId)
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

                        else -> error("Unexpected response request count ${responseRequests.size}.")
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(user, tokenCount = 1)
        agent.requestResponseApi().toList()

        assertEquals(1, responseRequests.size)
        assertRequestInput(responseRequests[0].request.input, user)
        assertEquals("${storage.id.toCodexThreadId()}:0", responseRequests[0].windowId)
        assertTrue(responseRequests[0].turnMetadata.contains("\"request_kind\":\"turn\""))
        assertEquals(0, compactRequests.size)
        assertEquals(partial, storage.history[2])
        assertEquals(20, storage.tokenCount[3])
        assertEquals(initialCheckpoint, storage.compaction[3])
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
    }

    test("resume never applies auto compaction policy across requests") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 20,
            ),
        )
        val user = userMessage("Answer once.")
        val firstFinal = assistantMessage("First final.")
        val compaction = ResponseItem.Compaction(encryptedContent = "next-turn-compact")
        val secondFinal = assistantMessage("Second final.")
        val compactRequests = mutableListOf<RecordedRemoteCompactionV2Request>()
        val responseRequests = mutableListOf<RecordedCreateResponse>()
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { request, installationId, turnMetadata, windowId ->
                    compactRequests += RecordedRemoteCompactionV2Request(
                        request = request,
                        installationId = installationId,
                        turnMetadata = turnMetadata,
                        windowId = windowId,
                    )
                    remoteCompactionV2Response(
                        compactionOutput = compaction,
                        usage = TokenUsage(20, 1, 21),
                    )
                }

                createResponse { request, installationId, turnMetadata, windowId ->
                    responseRequests += RecordedCreateResponse(request, installationId, turnMetadata, windowId)
                    when (responseRequests.size) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, firstFinal),
                            ResponsesStreamEvent.Completed(
                                Response(
                                    id = "response_1",
                                    usage = TokenUsage(16, 4, 20),
                                    endTurn = true,
                                ),
                            ),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, secondFinal),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected response request count ${responseRequests.size}.")
                    }
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(user, tokenCount = 1)
        agent.requestResponseApi().toList()

        assertEquals(1, responseRequests.size)
        assertEquals("${storage.id.toCodexThreadId()}:0", responseRequests.single().windowId)
        assertTrue(responseRequests.single().turnMetadata.contains("\"request_kind\":\"turn\""))
        assertEquals(0, compactRequests.size)
        assertEquals(firstFinal, storage.history[2])
        assertEquals(20, storage.tokenCount[3])

        agent.requestResponseApi().toList()

        assertEquals(2, responseRequests.size)
        assertEquals(0, compactRequests.size)
        assertRequestInput(responseRequests[1].request.input, user, firstFinal)
        assertEquals(secondFinal, storage.history[4])
    }

    test("remote compaction v2 client failure does not mutate storage") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { _, _, _, _ ->
                    throw IllegalStateException("bad remote compaction v2")
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(userMessage("Compact."))

        assertFailsWith<IllegalStateException> {
            agent.forcedCompact()
        }
        assertEquals(1, storage.latestIndex())
        assertEquals(CodexAgentStateValue.UserMessage, agent.state.value)
    }
    }
}

private data class RecordedCreateResponse(
    val request: ResponsesApiRequest,
    val installationId: String?,
    val turnMetadata: String,
    val windowId: String,
)

private data class RecordedRemoteCompactionV2Request(
    val request: ResponsesApiRequest,
    val installationId: String?,
    val turnMetadata: String,
    val windowId: String,
)

private val defaultCollaborationInput: ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Developer,
        content = listOf(ContentItem.InputText(ModeKind.Default.renderCollaborationMode())),
    )

private val defaultMultiAgentInput: ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Developer,
        content = listOf(ContentItem.InputText(ReasoningEffort.Medium.renderMultiAgentMode())),
    )

private fun assertRequestInput(
    actual: List<ResponseItem>,
    vararg durableItems: ResponseItem,
) {
    assertEquals(defaultCollaborationInput, actual[0])
    assertEquals(defaultMultiAgentInput, actual[1])
    assertEquals(durableItems.toList(), actual.takeLast(durableItems.size))
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

private fun testCompletedToolEvent(
    name: String,
    result: String,
): StableTextToolEvent =
    StableTextToolEvent(
        name = name,
        arguments = buildJsonObject {},
        result = result,
        success = true,
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

private fun instant(epochSecond: Long): Instant =
    Instant.fromEpochSeconds(epochSecond)

private fun remoteCompactionV2Response(
    compactionOutput: ResponseItem.Compaction,
    usage: TokenUsage? = null,
): RemoteCompactionV2Response =
    RemoteCompactionV2Response(
        compactionOutput = compactionOutput,
        completedResponse = usage?.let { Response(id = "compact_response", usage = it, endTurn = true) },
    )

private fun assertAdvancedCompactionCheckpoint(
    checkpoint: CompactionCheckpoint,
    prefix: List<ResponseItem.HistoryItem>,
    historyBaseIndex: Int,
    previousCheckpoint: CompactionCheckpoint,
) {
    assertEquals(prefix, checkpoint.prefix)
    assertEquals(historyBaseIndex, checkpoint.historyBaseIndex)
    assertEquals(previousCheckpoint.windowNumber + 1, checkpoint.windowNumber)
    assertEquals(previousCheckpoint.firstWindowId, checkpoint.firstWindowId)
    assertEquals(previousCheckpoint.windowId, checkpoint.previousWindowId)
    assertTrue(checkpoint.windowId != previousCheckpoint.windowId)
    assertEquals('7', checkpoint.windowId[14])
}
