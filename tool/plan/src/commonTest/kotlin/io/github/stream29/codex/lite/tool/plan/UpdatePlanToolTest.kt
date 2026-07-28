package io.github.stream29.codex.lite.tool.plan

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.plan.PlanTools
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals
import kotlin.test.assertIs

val updatePlanToolTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("tool commits the plan and matching output atomically") {
            val plan = UpdatePlanArgs(
                explanation = "Start implementation.",
                plan = listOf(PlanItemArg("Implement runtime", StepStatus.InProgress)),
            )
            val call = ResponseItem.FunctionCall(
                name = PlanTools.Name,
                arguments = OpenAiJsonCodec.encodeToString(plan),
                callId = "call_plan",
            )
            val client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, call),
                        ResponsesStreamEvent.Completed(
                            Response(id = "response_plan", endTurn = false),
                        ),
                    )
                }
            }
            val state = CodexAgentState(
                client = client,
                storage = InMemoryCodexAgentStorage(
                    CodexAgentSettings(model = OpenAiModelId("test-model")),
                ),
                contextSettings = TestAgentContextSettings,
                mcpService = TestMcpService(),
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Update the plan.")))
            state.requestResponseApi().toList()

            val output = assertIs<ResponseItem.FunctionCallOutput>(
                state.updatePlanTool().handle(call),
            )

            assertEquals(FunctionCallOutputBody.Text("Plan updated"), output.output.body)
            assertEquals(plan, state.storage.settings.latestValue().plan)
            assertEquals(CodexAgentStateValue.ToolCompleted, state.state.value)
            val outputs = state.storage.history.indexes().toList()
                .map { index -> state.storage.history[index] }
                .filterIsInstance<ResponseItem.FunctionCallOutput>()
            assertEquals(listOf(output), outputs)
        }
    }
}
