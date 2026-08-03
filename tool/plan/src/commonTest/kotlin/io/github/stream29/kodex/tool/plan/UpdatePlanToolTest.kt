package io.github.stream29.kodex.tool.plan

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.plan.PlanTools
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
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
        test("tool updates the plan and completes the matching output") {
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
            val state = KodexAgentState(
                client = client,
                storage = InMemoryKodexAgentStorage(
                    KodexAgentSettings(model = OpenAiModelId("test-model")),
                ),
                contextSettings = TestAgentContextSettings,
                mcpService = TestMcpService(),
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Update the plan.")))
            state.requestResponseApi().toList()

            val pending = assertIs<PendingPlanUpdate>(
                assertIs<KodexAgentStateValue.ToolPending>(state.state.value).events.single(),
            )
            val completed = assertIs<StablePlanUpdate>(state.updatePlanTool().handle(pending))

            assertEquals(StablePlanUpdate(callId = call.callId, arguments = plan), completed)
            assertEquals(plan, state.storage.settings.latestValue().plan)
            assertEquals(KodexAgentStateValue.ToolCompleted, state.state.value)
            val persisted = state.storage.stable.indexes().toList()
                .map { index -> state.storage.stable[index] }
                .filterIsInstance<StablePlanUpdate>()
                .single()
            assertEquals(completed, persisted)
        }
    }
}
