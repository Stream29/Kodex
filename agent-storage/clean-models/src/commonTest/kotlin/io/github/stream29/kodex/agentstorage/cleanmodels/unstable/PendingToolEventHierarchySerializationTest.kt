package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchQuery
import io.github.stream29.kodex.openai.SearchResponseLength
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageDetail
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
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
        assertEquals(event.toResponseHistoryItems(), listOf(
            ResponseItem.FunctionCall(
                id = ResponseItemId("item_function"),
                callId = "call_function",
                name = "search",
                arguments = """{"query":"design context"}""",
            ),
        ))
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
            PendingMcpToolEvent(
                callId = "call_mcp",
                itemId = ResponseItemId("item_mcp"),
                name = "search",
                namespace = "google_drive",
                arguments = buildJsonObject {
                    put("query", "design context")
                },
            ) to "mcp",
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
            PendingToolSearchEvent(
                callId = "call_tool_search",
                itemId = ResponseItemId("item_tool_search"),
                arguments = SearchToolCallParams("connected drive tools", limit = 4),
            ) to "tool_search",
            PendingInvalidToolCall(
                callId = "call_invalid_tool",
                itemId = ResponseItemId("item_invalid_tool"),
                invocation = PendingInvalidToolInvocation.ToolSearch(
                    buildJsonObject {
                        put("query", "connected drive tools")
                        put("limit", "invalid")
                    },
                ),
                message = "failed to parse tool_search arguments",
            ) to "invalid_tool_call",
            PendingImageViewToolEvent(
                callId = "call_image_view",
                itemId = ResponseItemId("item_image_view"),
                arguments = ViewImageToolArguments(
                    path = "/tmp/chart.png",
                    detail = ViewImageDetail.Original,
                    environmentId = "local",
                ),
            ) to "image_view",
            PendingImageGenerationToolEvent(
                callId = "call_image_generation",
                itemId = ResponseItemId("item_image_generation"),
                arguments = ImageGenToolArguments(
                    prompt = "Draw an architecture diagram.",
                    referencedImagePaths = listOf("reference.png"),
                ),
            ) to "image_generation",
            PendingCommandExecutionToolEvent(
                callId = "call_command_execution",
                itemId = ResponseItemId("item_command_execution"),
                action = PendingCommandExecutionAction.ExecCommand(
                    ExecCommandArguments(
                        command = "./gradlew check",
                        workdir = "Kodex",
                        tty = true,
                        maxOutputTokens = 20_000,
                    ),
                ),
            ) to "command_execution",
            PendingRequestUserInputToolEvent(
                callId = "call_request_user_input",
                itemId = ResponseItemId("item_request_user_input"),
                arguments = RequestUserInputArgs(
                    questions = listOf(
                        RequestUserInputQuestion(
                            id = "scope",
                            header = "Scope",
                            question = "Which scope should be used?",
                            isOther = true,
                            options = listOf(
                                RequestUserInputQuestionOption(
                                    label = "Current module",
                                    description = "Only update clean-models.",
                                ),
                            ),
                        ),
                    ),
                    autoResolutionMs = 60_000,
                ),
            ) to "request_user_input",
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
            PendingWebSearchToolEvent(
                callId = "call_web_search",
                itemId = ResponseItemId("item_web_search"),
                commands = SearchCommands(
                    searchQuery = listOf(
                        SearchQuery(
                            q = "Kotlin serialization",
                            domains = listOf("kotlinlang.org"),
                        ),
                    ),
                    responseLength = SearchResponseLength.Short,
                ),
            ) to "web_search",
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
            PendingInvalidToolCall(
                callId = "call_tool_search",
                invocation = PendingInvalidToolInvocation.ToolSearch(
                    buildJsonObject {
                        put("query", "connected tools")
                        put("limit", "invalid")
                    },
                ),
                message = "failed to parse tool_search arguments",
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
