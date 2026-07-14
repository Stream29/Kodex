package io.github.stream29.codex.lite.tool.builder

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.tool.contract.ToolCallPayload
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals

@Serializable
private data class JsonToolInput(val value: String)

@Serializable
private data class JsonToolOutput(val echoed: String)

private val jsonToolTestSpec = ResponsesApiTool(
    name = "echo",
    description = "Echoes input.",
    parameters = PropertyBuilder().obj {
        additionalProperties = false
        property("value") {
            required = true
            string { description = "Value to echo." }
        }
    },
)

val jsonToolBuilderTest by testSuite {
    test("decodes json payload and encodes json result") {
        val tool = jsonTool(
            spec = jsonToolTestSpec,
            inputDeserializer = JsonToolInput.serializer(),
            outputSerializer = JsonToolOutput.serializer(),
        ) { input ->
            jsonToolSuccess(JsonToolOutput(input.value))
        }

        assertEquals(
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(
                    ToolBuilderJson.encodeToString(JsonToolOutput.serializer(), JsonToolOutput("hello")),
                ),
                success = true,
            ),
            tool.handle(
                ToolCallPayload.FunctionCall(
                    ResponseItem.FunctionCall(
                        name = "echo",
                        arguments = """{"value":"hello"}""",
                        callId = "call_1",
                    ),
                ),
            ),
        )
    }

    test("rejects custom payload for json tool") {
        val tool = jsonTool(
            spec = jsonToolTestSpec,
            inputDeserializer = JsonToolInput.serializer(),
            outputSerializer = JsonToolOutput.serializer(),
        ) { input ->
            jsonToolSuccess(JsonToolOutput(input.value))
        }

        assertEquals(
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text("JSON tool received custom tool payload"),
                success = false,
            ),
            tool.handle(
                ToolCallPayload.CustomToolCall(
                    ResponseItem.CustomToolCall(
                        callId = "call_1",
                        name = "echo",
                        input = "raw",
                    ),
                ),
            ),
        )
    }

    test("closes without resources") {
        val tool = jsonTool(
            spec = jsonToolTestSpec,
            inputDeserializer = JsonToolInput.serializer(),
            outputSerializer = JsonToolOutput.serializer(),
        ) { input ->
            jsonToolSuccess(JsonToolOutput(input.value))
        }

        tool.close()
    }
}
