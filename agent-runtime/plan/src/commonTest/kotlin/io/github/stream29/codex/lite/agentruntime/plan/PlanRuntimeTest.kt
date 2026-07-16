package io.github.stream29.codex.lite.agentruntime.plan

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentEnvironment
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.plan.PlanTools
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val planTestContextPrefixProvider: AgentContextPrefixProvider =
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
                currentDate = LocalDate(2026, 7, 16),
                timeZone = TimeZone.UTC,
            )

        override val availableSkills: List<AvailableSkill> = emptyList()

        override val agentMd: List<AgentsMdInstruction> = emptyList()
    }

val planRuntimeTest by testSuite {
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
                tools = listOf(PlanTools.spec),
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
            contextPrefixProvider = planTestContextPrefixProvider,
        )

        state.appendUserMessage(listOf(ContentItem.InputText("Make a plan.")))
        state.compactionRuntime().planRuntime().resume().toList()

        assertEquals(2, requests.size)
        assertEquals(listOf(PlanTools.spec), requests[0].tools)
        assertEquals(listOf(PlanTools.spec), requests[1].tools)
        assertEquals(plan, storage.settings[storage.latestIndex()].plan)
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
                tools = listOf(PlanTools.spec),
                collaborationMode = ModeKind.Plan,
            ),
        )
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
            contextPrefixProvider = planTestContextPrefixProvider,
        )

        state.appendUserMessage(listOf(ContentItem.InputText("Make a plan.")))
        state.compactionRuntime().planRuntime().resume().toList()

        assertEquals(2, requestCount)
        assertEquals(UpdatePlanArgs(plan = emptyList()), storage.settings[storage.latestIndex()].plan)
        val output = assertIs<ResponseItem.FunctionCallOutput>(storage.history[3])
        assertEquals(false, output.output.success)
        assertTrue((output.output.body as FunctionCallOutputBody.Text).text.contains("not allowed in Plan mode"))
        assertFalse(state.state.value is CodexAgentStateValue.ToolPending)
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
            contextPrefixProvider = planTestContextPrefixProvider,
        )

        state.appendUserMessage(listOf(ContentItem.InputText("Call another tool.")))
        state.compactionRuntime().planRuntime().resume().toList()

        assertEquals(1, requests.size)
        assertTrue(requests.single().tools.isEmpty())
        assertTrue(storage.settings[storage.latestIndex()].tools.isEmpty())
        assertEquals(
            listOf(call),
            assertIs<CodexAgentStateValue.ToolPending>(state.state.value).calls,
        )
    }
}

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = io.github.stream29.codex.lite.openai.MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )
