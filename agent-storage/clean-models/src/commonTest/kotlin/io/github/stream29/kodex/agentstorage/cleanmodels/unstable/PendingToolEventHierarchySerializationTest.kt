package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.applypatch.parsePatch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals

private val pendingHierarchyJson = Json

val pendingToolEventHierarchySerializationTest by testSuite {
    test("round trips a pending hosted server tool search") {
        val call = ResponseItem.ServerToolSearchCall(
            id = ResponseItemId("search_call"),
            status = "in_progress",
            arguments = buildJsonObject {
                put("query", "connected drive tools")
            },
        )
        val event: UnstableCleanEvent = PendingServerToolSearch(call)

        val encoded = pendingHierarchyJson.encodeToString<UnstableCleanEvent>(event)
        val element = pendingHierarchyJson.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("server_tool_search"), element["type"])
        assertEquals(event, pendingHierarchyJson.decodeFromString<UnstableCleanEvent>(encoded))
        assertEquals(listOf(call), event.toResponseHistoryItems())
    }

    test("round trips a client pending tool through the unstable root") {
        val event: UnstableCleanEvent = PendingFunctionToolEvent(
            callId = "call_function",
            itemId = ResponseItemId("item_function"),
            name = "search",
            arguments = buildJsonObject {
                put("query", "design context")
            },
        )

        val encoded = pendingHierarchyJson.encodeToString<UnstableCleanEvent>(event)

        assertEquals(event, pendingHierarchyJson.decodeFromString<UnstableCleanEvent>(encoded))
        assertEquals(
            event.toResponseHistoryItems(),
            listOf(
                ResponseItem.FunctionCall(
                    id = ResponseItemId("item_function"),
                    callId = "call_function",
                    name = "search",
                    arguments = """{"query":"design context"}""",
                ),
            ),
        )
    }

    test("round trips every pending tool event kind") {
        val events: List<Pair<PendingToolEvent, String>> = listOf(
            PendingFunctionToolEvent(
                callId = "call_function",
                itemId = ResponseItemId("item_function"),
                name = "search",
                namespace = "google_drive",
                arguments = buildJsonObject {
                    put("query", "design context")
                },
            ) to "function",
            PendingCustomToolEvent(
                callId = "call_custom",
                itemId = ResponseItemId("item_custom"),
                name = "custom_tool",
                input = "freeform input",
            ) to "custom",
            PendingPatchToolEvent(
                callId = "call_apply_patch",
                itemId = ResponseItemId("item_apply_patch"),
                diff = """
                    *** Begin Patch
                    *** Delete File: obsolete.txt
                    *** End Patch
                """.trimIndent().parsePatch(),
            ) to "apply_patch",
            PendingInvalidToolCall(
                callId = "call_invalid_tool",
                itemId = ResponseItemId("item_invalid_tool"),
                invocation = PendingInvalidToolInvocation.Function(
                    name = "tool_search",
                    arguments = """{"query":"connected drive tools","limit":"invalid"}""",
                ),
                message = "failed to parse function arguments",
            ) to "invalid_tool_call",
            PendingReadMediaToolEvent(
                callId = "call_read_media",
                itemId = ResponseItemId("item_read_media"),
                arguments = ReadMediaToolArguments(
                    path = "/tmp/chart.png",
                    detail = MediaDetail.Original,
                ),
            ) to "read_media",
            PendingAwaitEventToolEvent(
                callId = "call_await_event",
                itemId = ResponseItemId("item_await_event"),
                arguments = AwaitEventArguments(terminals = listOf(2)),
            ) to "await_event",
            PendingCommandExecutionToolEvent(
                callId = "call_command_execution",
                itemId = ResponseItemId("item_command_execution"),
                action = PendingCommandExecutionAction.ExecCommand(
                    ExecCommandArguments(
                        command = "./gradlew check",
                        workdir = "Kodex",
                        yieldTimeMillis = 10_000,
                        maxOutputTokens = 20_000,
                    ),
                ),
            ) to "command_execution",
            PendingPlanUpdate(
                callId = "call_plan_update",
                itemId = ResponseItemId("item_plan_update"),
                arguments = UpdatePlanArgs(
                    explanation = "Current plan",
                    plan = listOf(
                        PlanItemArg("Migrate clean tools", StepStatus.InProgress),
                    ),
                ),
            ) to "plan_update",
        )

        events.forEach { (event, serialName) ->
            val encoded = pendingHierarchyJson.encodeToString<PendingToolEvent>(event)
            val element = pendingHierarchyJson.parseToJsonElement(encoded).jsonObject

            assertEquals(JsonPrimitive(serialName), element["type"])
            assertEquals(
                event,
                pendingHierarchyJson.decodeFromString<PendingToolEvent>(encoded),
            )
        }
    }

    test("round trips every pending invalid tool-call kind") {
        val calls: List<PendingInvalidToolCall> = listOf(
            PendingInvalidToolCall(
                callId = "call_function",
                invocation = PendingInvalidToolInvocation.Function(
                    name = "broken_tool",
                    arguments = """{"missing":""",
                ),
                message = "failed to parse function arguments",
            ),
            PendingInvalidToolCall(
                callId = "call_custom",
                invocation = PendingInvalidToolInvocation.Custom(
                    name = "apply_patch",
                    input = "not a patch",
                ),
                message = "invalid patch",
            ),
        )

        calls.forEach { call ->
            val encoded = pendingHierarchyJson.encodeToString<PendingToolEvent>(call)
            val element = pendingHierarchyJson.parseToJsonElement(encoded).jsonObject

            assertEquals(JsonPrimitive("invalid_tool_call"), element["type"])
            assertEquals(
                call,
                pendingHierarchyJson.decodeFromString<PendingToolEvent>(encoded),
            )
        }
    }
}
