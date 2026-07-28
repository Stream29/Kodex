package io.github.stream29.codex.lite.agentruntime.plan

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestContextPrefixProvider
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.hook.contract.tool.HookToolInvocation
import io.github.stream29.codex.lite.hook.contract.tool.NoOpToolHooks
import io.github.stream29.codex.lite.hook.contract.tool.PostToolUseRequest
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.plan.PlanTools
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val planRuntimeTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("runtime persists a parsed plan with its tool output") {
        val plan = UpdatePlanArgs(
            explanation = "Start implementation.",
            plan = listOf(PlanItemArg("Implement runtime", StepStatus.InProgress)),
        )
        val call = ResponseItem.FunctionCall(
            name = PlanTools.Name,
            arguments = OpenAiJsonCodec.encodeToString(plan),
            callId = "call_plan",
        )
        val final = assistantMessage("Plan recorded.")
        val requests = mutableListOf<ResponsesApiRequest>()
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
            ),
        )
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    when (requests.size) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, call),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, final),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected request count ${requests.size}.")
                    }
                }
            },
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )

        state.appendUserMessage(listOf(ContentItem.InputText("Make a plan.")))
        state.compactionRuntime(testModelCatalog()).planRuntime(NoOpToolHooks).resume().toList()

        assertEquals(2, requests.size)
        assertEquals(requests[0].tools, requests[1].tools)
        assertTrue(requests.all { request -> PlanTools.spec in request.tools })
        assertEquals(plan, storage.settings.latestValue().plan)
        val output = assertIs<ResponseItem.FunctionCallOutput>(storage.history[3])
        assertEquals(true, output.output.success)
        assertEquals("Plan updated", (output.output.body as FunctionCallOutputBody.Text).text)
        assertEquals(final, storage.history[4])
        assertEquals(CodexAgentStateValue.AssistantMessage, state.state.value)
    }

    test("plan mode returns a tool failure without changing the stored plan") {
        val plan = UpdatePlanArgs(
            plan = listOf(PlanItemArg("Do not store this", StepStatus.Pending)),
        )
        val call = ResponseItem.FunctionCall(
            name = PlanTools.Name,
            arguments = OpenAiJsonCodec.encodeToString(plan),
            callId = "call_plan",
        )
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                collaborationMode = ModeKind.Plan,
            ),
        )
        val requests = mutableListOf<ResponsesApiRequest>()
        val toolHooks = object : ToolHooks {
            override suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult =
                PreToolUseResult.Continue

            override suspend fun onPostToolUse(request: PostToolUseRequest): Unit =
                error("Plan mode must reject update_plan before PostToolUse.")
        }
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    when (requests.size) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, call),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage("Done.")),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected request count ${requests.size}.")
                    }
                }
            },
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )

        state.appendUserMessage(listOf(ContentItem.InputText("Make a plan.")))
        state.compactionRuntime(testModelCatalog()).planRuntime(toolHooks).resume().toList()

        assertEquals(2, requests.size)
        assertTrue(requests.all { request -> PlanTools.spec !in request.tools })
        assertEquals(UpdatePlanArgs(plan = emptyList()), storage.settings.latestValue().plan)
        val output = assertIs<ResponseItem.FunctionCallOutput>(storage.history[3])
        assertEquals(false, output.output.success)
        assertTrue((output.output.body as FunctionCallOutputBody.Text).text.contains("not allowed in Plan mode"))
        assertFalse(state.state.value is CodexAgentStateValue.ToolPending)
    }

    test("runtime rejects unknown plan fields") {
        val call = ResponseItem.FunctionCall(
            name = PlanTools.Name,
            arguments = """{"plan":[],"unexpected":true}""",
            callId = "call_plan",
        )
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        var requestCount = 0
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    requestCount += 1
                    when (requestCount) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, call),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage("Done.")),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected request count $requestCount.")
                    }
                }
            },
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )

        state.appendUserMessage(listOf(ContentItem.InputText("Make a plan.")))
        state.compactionRuntime(testModelCatalog()).planRuntime(NoOpToolHooks).resume().toList()

        val output = assertIs<ResponseItem.FunctionCallOutput>(storage.history[3])
        assertEquals(false, output.output.success)
        assertTrue(
            (output.output.body as FunctionCallOutputBody.Text).text
                .startsWith("failed to parse function arguments:"),
        )
        assertEquals(UpdatePlanArgs(plan = emptyList()), storage.settings.latestValue().plan)
        assertFalse(state.state.value is CodexAgentStateValue.ToolPending)
        assertEquals(2, requestCount)
    }

    test("runtime leaves unowned calls pending without registering its spec") {
        val call = ResponseItem.FunctionCall(
            name = "unowned_tool",
            arguments = "{}",
            callId = "call_unowned",
        )
        val requests = mutableListOf<ResponsesApiRequest>()
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, call),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            },
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )

        state.appendUserMessage(listOf(ContentItem.InputText("Call another tool.")))
        state.compactionRuntime(testModelCatalog()).planRuntime(NoOpToolHooks).resume().toList()

        assertEquals(1, requests.size)
        assertTrue(
            requests.single().tools.none { spec ->
                spec is ResponsesApiTool && spec.name == call.name
            },
        )
        assertEquals(
            listOf(call),
            assertIs<CodexAgentStateValue.ToolPending>(state.state.value).calls,
        )
    }
    }
}

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = io.github.stream29.codex.lite.openai.MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private fun testModelCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
        codexCliStorage = CodexCliStorage(Path(".codex-lite-test-model-catalog")),
    )
