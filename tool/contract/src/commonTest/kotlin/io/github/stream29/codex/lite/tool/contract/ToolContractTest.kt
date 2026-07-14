package io.github.stream29.codex.lite.tool.contract

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals



private val json = Json { encodeDefaults = true }

val toolContractTest by testSuite {
    test("tool name renders plain and namespaced names") {
        assertEquals("apply_patch", ToolName.plain("apply_patch").toString())
        assertEquals("web.run", ToolName.namespaced("web", "run").toString())
    }

    test("function call payload keeps raw argument text") {
        val payload = ToolCallPayload.FunctionCall(
            ResponseItem.FunctionCall(
                name = "search",
                arguments = """{"query":"hello"}""",
                callId = "call_1",
            ),
        )

        assertEquals("""{"query":"hello"}""", payload.logPayload)
    }

    test("payload union is serializable") {
        val encoded = json.encodeToString<ToolCallPayload>(
            ToolCallPayload.CustomToolCall(
                ResponseItem.CustomToolCall(
                    callId = "call_1",
                    name = "apply_patch",
                    input = "patch",
                ),
            ),
        )

        assertEquals(
            JsonPrimitive("custom_tool_call"),
            json.parseToJsonElement(encoded).jsonObject["type"],
        )
    }

    test("tool call result uses open ai output payload") {
        val result: ToolCallResult = FunctionCallOutputPayload(
            body = FunctionCallOutputBody.Text("ok"),
            success = false,
        )

        assertEquals(
            "ok",
            (result.body as FunctionCallOutputBody.Text).text,
        )
        assertEquals(false, result.success)
    }
}
