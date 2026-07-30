package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val toolEventJson = Json

val stableToolEventSerializationTest by testSuite {
    test("function fallbacks reconstruct calls and typed outputs") {
        val arguments = buildJsonObject { put("query", "design context") }
        val events: List<StableCleanEvent.CompletedTool> = listOf(
            StableJsonToolEvent(
                callId = "call_json",
                itemId = ResponseItemId("item_json"),
                name = "search",
                namespace = "google_drive",
                arguments = arguments,
                result = buildJsonObject { put("matches", 1) },
                success = true,
            ),
            StableTextToolEvent(
                callId = "call_text",
                itemId = ResponseItemId("item_text"),
                name = "search",
                namespace = "google_drive",
                arguments = arguments,
                result = "one match",
                success = true,
            ),
        )

        events.forEach(::assertToolEventRoundTripWithoutRawItems)
        assertEquals(
            """{"matches":1}""",
            events[0].toResponseHistoryItems()
                .filterIsInstance<ResponseItem.FunctionCallOutput>()
                .single()
                .output
                .text(),
        )
        assertEquals(
            "one match",
            events[1].toResponseHistoryItems()
                .filterIsInstance<ResponseItem.FunctionCallOutput>()
                .single()
                .output
                .text(),
        )
    }

    test("custom fallback retains only irreducible payloads") {
        val customResult = FunctionCallOutputPayload.fromText("done")
        val event = StableCustomToolEvent(
            callId = "call_custom",
            itemId = ResponseItemId("item_custom"),
            name = "custom_tool",
            input = "freeform input",
            result = customResult,
            success = true,
        )

        assertToolEventRoundTripWithoutRawItems(event)
        assertEquals(
            listOf(
                ResponseItem.CustomToolCall(
                    id = ResponseItemId("item_custom"),
                    callId = "call_custom",
                    name = "custom_tool",
                    input = "freeform input",
                ),
                ResponseItem.CustomToolCallOutput(
                    callId = "call_custom",
                    output = customResult.copy(success = true),
                ),
            ),
            event.toResponseHistoryItems(),
        )
    }

    test("invalid tool calls preserve each protocol call kind") {
        val functionMessage = "failed to parse function arguments"
        val customMessage = "invalid patch"
        val searchMessage = "failed to parse tool_search arguments"
        val searchArguments = buildJsonObject {
            put("query", "connected tools")
            put("limit", "invalid")
        }
        val events: List<StableCleanEvent.InvalidToolCall> = listOf(
            StableCleanEvent.InvalidToolCall(
                callId = "call_function",
                itemId = ResponseItemId("item_function"),
                invocation = InvalidToolInvocation.Function(
                    name = "search",
                    namespace = "google_drive",
                    arguments = """{"query":42}""",
                ),
                message = functionMessage,
            ),
            StableCleanEvent.InvalidToolCall(
                callId = "call_custom",
                itemId = ResponseItemId("item_custom"),
                invocation = InvalidToolInvocation.Custom(
                    name = "apply_patch",
                    input = "not a patch",
                ),
                message = customMessage,
            ),
            StableCleanEvent.InvalidToolCall(
                callId = "call_tool_search",
                itemId = ResponseItemId("item_tool_search"),
                invocation = InvalidToolInvocation.ToolSearch(searchArguments),
                message = searchMessage,
            ),
        )

        events.forEach(::assertToolEventRoundTripWithoutRawItems)
        events.forEach { event ->
            val element = toolEventJson.parseToJsonElement(
                toolEventJson.encodeToString<StableCleanEvent>(event),
            ).jsonObject
            assertEquals(JsonPrimitive("invalid_tool_call"), element["type"])
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
                ResponseItem.FunctionCallOutput(
                    callId = "call_function",
                    output = FunctionCallOutputPayload.fromText(functionMessage)
                        .copy(success = false),
                ),
                ResponseItem.CustomToolCall(
                    id = ResponseItemId("item_custom"),
                    callId = "call_custom",
                    name = "apply_patch",
                    input = "not a patch",
                ),
                ResponseItem.CustomToolCallOutput(
                    callId = "call_custom",
                    output = FunctionCallOutputPayload.fromText(customMessage)
                        .copy(success = false),
                ),
                ResponseItem.ClientToolSearchCall(
                    id = ResponseItemId("item_tool_search"),
                    callId = "call_tool_search",
                    arguments = searchArguments,
                ),
                ResponseItem.ClientToolSearchOutput(
                    callId = "call_tool_search",
                    status = "completed",
                    tools = emptyList(),
                ),
            ),
            events.flatMap(StableCleanEvent::toResponseHistoryItems),
        )
    }

    test("MCP event reconstructs its concrete result envelope") {
        val result = CallToolResult(
            content = listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", "Found one document.")
                },
            ),
            structuredContent = buildJsonObject {
                put("document_id", "doc-1")
            },
            isError = false,
        )
        val arguments = buildJsonObject {
            put("query", "design context")
        }
        val event: StableCleanEvent = StableMcpToolEvent(
            callId = "call_mcp",
            itemId = ResponseItemId("item_mcp"),
            name = "search",
            namespace = "google_drive",
            arguments = arguments,
            result = result,
        )

        val encoded = toolEventJson.encodeToString(event)
        val element = toolEventJson.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("mcp_tool_event"), element["type"])
        assertTrue("call" !in element)
        assertTrue("output" !in element)
        assertEquals(event, toolEventJson.decodeFromString<StableCleanEvent>(encoded))
        assertEquals(
            listOf(
                ResponseItem.FunctionCall(
                    id = ResponseItemId("item_mcp"),
                    callId = "call_mcp",
                    name = "search",
                    namespace = "google_drive",
                    arguments = """{"query":"design context"}""",
                ),
                ResponseItem.McpToolCallOutput(
                    callId = "call_mcp",
                    output = result,
                ),
            ),
            event.toResponseHistoryItems(),
        )
    }
}

private fun assertToolEventRoundTripWithoutRawItems(
    event: StableCleanEvent.CompletedTool,
) {
    val encoded = toolEventJson.encodeToString<StableCleanEvent>(event)
    val element = toolEventJson.parseToJsonElement(encoded).jsonObject

    assertTrue("call" !in element)
    assertTrue("output" !in element)
    assertEquals(event, toolEventJson.decodeFromString<StableCleanEvent>(encoded))
    assertEquals(2, event.toResponseHistoryItems().size)
}

private fun FunctionCallOutputPayload.text(): String =
    (body as FunctionCallOutputBody.Text).text
