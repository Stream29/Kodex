package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.codex.lite.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolArguments
import io.github.stream29.codex.lite.utils.applypatch.parsePatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val pendingEventJson = Json

val pendingToolEventSerializationTest by testSuite {
    test("round trips typed pending events without duplicate raw calls") {
        val patchText = """
            *** Begin Patch
            *** Delete File: obsolete.txt
            *** End Patch
        """.trimIndent()
        val events: List<PendingToolEvent> = listOf(
            PendingFunctionToolEvent(
                callId = "call_function",
                itemId = ResponseItemId("item_function"),
                name = "search",
                namespace = "google_drive",
                arguments = buildJsonObject {
                    put("query", "design context")
                },
            ),
            PendingCustomToolEvent(
                callId = "call_custom",
                itemId = ResponseItemId("item_custom"),
                name = "custom_tool",
                input = "freeform input",
            ),
            PendingPatchToolEvent(
                callId = "call_apply_patch",
                itemId = ResponseItemId("item_apply_patch"),
                diff = patchText.parsePatch(),
            ),
            PendingToolSearchEvent(
                callId = "call_tool_search",
                itemId = ResponseItemId("item_tool_search"),
                arguments = SearchToolCallParams(query = "connected tools"),
            ),
        )

        events.forEach { event ->
            val encoded = pendingEventJson.encodeToString(event)
            val element = pendingEventJson.parseToJsonElement(encoded).jsonObject
            val projected = event.toResponseHistoryItems().single() as ResponseItem.ToolCall

            assertEquals(event.callId, projected.callId)
            assertEquals(event.itemId, projected.itemId())
            assertEquals(true, "type" in element)
            assertEquals(event, pendingEventJson.decodeFromString<PendingToolEvent>(encoded))
        }

        assertEquals(
            listOf(
                ResponseItem.FunctionCall(
                    id = ResponseItemId("item_function"),
                    callId = "call_function",
                    name = "search",
                    namespace = "google_drive",
                    arguments = """{"query":"design context"}""",
                ),
                ResponseItem.CustomToolCall(
                    id = ResponseItemId("item_custom"),
                    callId = "call_custom",
                    name = "custom_tool",
                    input = "freeform input",
                ),
                ResponseItem.CustomToolCall(
                    id = ResponseItemId("item_apply_patch"),
                    callId = "call_apply_patch",
                    name = "apply_patch",
                    input = patchText,
                ),
                ResponseItem.ClientToolSearchCall(
                    id = ResponseItemId("item_tool_search"),
                    callId = "call_tool_search",
                    arguments = buildJsonObject {
                        put("query", "connected tools")
                    },
                ),
            ),
            events.flatMap(PendingToolEvent::toResponseHistoryItems),
        )
    }

    test("pending invalid calls preserve each protocol call kind") {
        val searchArguments = buildJsonObject {
            put("query", "connected tools")
            put("limit", "invalid")
        }
        val calls = listOf(
            PendingInvalidToolCall(
                callId = "call_function",
                itemId = ResponseItemId("item_function"),
                invocation = PendingInvalidToolInvocation.Function(
                    name = "search",
                    namespace = "google_drive",
                    arguments = """{"query":42}""",
                ),
                message = "failed to parse function arguments",
            ),
            PendingInvalidToolCall(
                callId = "call_custom",
                itemId = ResponseItemId("item_custom"),
                invocation = PendingInvalidToolInvocation.Custom(
                    name = "apply_patch",
                    input = "not a patch",
                ),
                message = "invalid patch",
            ),
            PendingInvalidToolCall(
                callId = "call_tool_search",
                itemId = ResponseItemId("item_tool_search"),
                invocation = PendingInvalidToolInvocation.ToolSearch(searchArguments),
                message = "failed to parse tool_search arguments",
            ),
        )

        calls.forEach { call ->
            val encoded = pendingEventJson.encodeToString<PendingToolEvent>(call)
            val projected = call.toResponseHistoryItems().single()

            assertEquals(call, pendingEventJson.decodeFromString<PendingToolEvent>(encoded))
            assertEquals(call.callId, (projected as ResponseItem.ToolCall).callId)
        }
        assertEquals(
            listOf(
                ResponseItem.FunctionCall(
                    id = ResponseItemId("item_function"),
                    callId = "call_function",
                    name = "search",
                    namespace = "google_drive",
                    arguments = """{"query":42}""",
                ),
                ResponseItem.CustomToolCall(
                    id = ResponseItemId("item_custom"),
                    callId = "call_custom",
                    name = "apply_patch",
                    input = "not a patch",
                ),
                ResponseItem.ClientToolSearchCall(
                    id = ResponseItemId("item_tool_search"),
                    callId = "call_tool_search",
                    arguments = searchArguments,
                ),
            ),
            calls.flatMap(PendingToolEvent::toResponseHistoryItems),
        )
    }

    test("projects every specialized function identity") {
        val cases: List<Pair<PendingToolEvent, Pair<String, String?>>> = listOf(
            PendingMcpToolEvent(
                callId = "call_mcp",
                name = "search",
                namespace = "google_drive",
                arguments = buildJsonObject { put("query", "context") },
            ) to ("search" to "google_drive"),
            PendingImageViewToolEvent(
                callId = "call_image_view",
                arguments = ViewImageToolArguments("image.png"),
            ) to ("view_image" to null),
            PendingImageGenerationToolEvent(
                callId = "call_image_generation",
                arguments = ImageGenToolArguments("Draw an image."),
            ) to ("imagegen" to "image_gen"),
            PendingCommandExecutionToolEvent(
                callId = "call_exec",
                action = PendingCommandExecutionAction.ExecCommand(
                    ExecCommandArguments("pwd"),
                ),
            ) to ("exec_command" to null),
            PendingCommandExecutionToolEvent(
                callId = "call_write",
                action = PendingCommandExecutionAction.WriteStdin(
                    WriteStdinArguments(7),
                ),
            ) to ("write_stdin" to null),
            PendingMultiAgentToolEvent(
                callId = "call_spawn",
                operation = PendingMultiAgentInvocation.SpawnAgent(
                    SpawnAgentArgs("worker", "Review this."),
                ),
            ) to ("spawn_agent" to null),
            PendingMultiAgentToolEvent(
                callId = "call_send",
                operation = PendingMultiAgentInvocation.SendMessage(
                    SendMessageArgs("/root/worker", "Continue."),
                ),
            ) to ("send_message" to null),
            PendingMultiAgentToolEvent(
                callId = "call_followup",
                operation = PendingMultiAgentInvocation.FollowupTask(
                    FollowupTaskArgs("/root/worker", "Review tests."),
                ),
            ) to ("followup_task" to null),
            PendingMultiAgentToolEvent(
                callId = "call_wait",
                operation = PendingMultiAgentInvocation.WaitAgent(WaitAgentArgs()),
            ) to ("wait_agent" to null),
            PendingMultiAgentToolEvent(
                callId = "call_interrupt",
                operation = PendingMultiAgentInvocation.InterruptAgent(
                    InterruptAgentArgs("/root/worker"),
                ),
            ) to ("interrupt_agent" to null),
            PendingMultiAgentToolEvent(
                callId = "call_list",
                operation = PendingMultiAgentInvocation.ListAgents(ListAgentsArgs()),
            ) to ("list_agents" to null),
            PendingRequestUserInputToolEvent(
                callId = "call_request_user_input",
                arguments = RequestUserInputArgs(emptyList()),
            ) to ("request_user_input" to null),
            PendingPlanUpdate(
                callId = "call_update_plan",
                arguments = UpdatePlanArgs(
                    explanation = null,
                    plan = listOf(PlanItemArg("Review", StepStatus.InProgress)),
                ),
            ) to ("update_plan" to null),
            PendingWebSearchToolEvent(
                callId = "call_web",
                commands = SearchCommands(),
            ) to ("run" to "web"),
        )

        cases.forEach { (event, identity) ->
            val call = assertIs<ResponseItem.FunctionCall>(
                event.toResponseHistoryItems().single(),
            )

            assertEquals(identity.first, call.name)
            assertEquals(identity.second, call.namespace)
            assertEquals(event.callId, call.callId)
        }
    }
}

private fun ResponseItem.ToolCall.itemId(): ResponseItemId? =
    when (this) {
        is ResponseItem.FunctionCall -> id
        is ResponseItem.CustomToolCall -> id
        is ResponseItem.ClientToolSearchCall -> id
    }
