package io.github.stream29.codex.lite.tool.builder

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.tool.contract.ToolCallPayload
import kotlinx.coroutines.test.runTest
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonToolBuilderTest {
    @Serializable
    private data class Input(val value: String)

    @Serializable
    private data class Output(val echoed: String)

    @Test
    fun decodesJsonPayloadAndEncodesJsonResult() = runTest {
        val tool = jsonTool(
            spec = testSpec,
            inputDeserializer = Input.serializer(),
            outputSerializer = Output.serializer(),
        ) { input ->
            jsonToolSuccess(Output(input.value))
        }

        assertEquals(
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(
                    ToolBuilderJson.encodeToString(Output.serializer(), Output("hello")),
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

    @Test
    fun rejectsCustomPayloadForJsonTool() = runTest {
        val tool = jsonTool(
            spec = testSpec,
            inputDeserializer = Input.serializer(),
            outputSerializer = Output.serializer(),
        ) { input ->
            jsonToolSuccess(Output(input.value))
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

    @Test
    fun closesWithoutResources() {
        val tool = jsonTool(
            spec = testSpec,
            inputDeserializer = Input.serializer(),
            outputSerializer = Output.serializer(),
        ) { input ->
            jsonToolSuccess(Output(input.value))
        }

        tool.close()
    }

    private companion object {
        val testSpec = ResponsesApiTool(
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
    }
}
