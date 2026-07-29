package io.github.stream29.codex.lite.tool.builder

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ImageDetail
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
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
            ResponseItem.FunctionCallOutput(
                callId = "call_1",
                output = FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text(
                        ToolBuilderJson.encodeToString(JsonToolOutput.serializer(), JsonToolOutput("hello")),
                    ),
                    success = true,
                ),
            ),
            tool.handle(
                ResponseItem.FunctionCall(
                    name = "echo",
                    arguments = """{"value":"hello"}""",
                    callId = "call_1",
                ),
            ).first,
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
            ResponseItem.CustomToolCallOutput(
                callId = "call_1",
                output = FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text("JSON tool received custom tool payload"),
                    success = false,
                ),
            ),
            tool.handle(
                ResponseItem.CustomToolCall(
                    callId = "call_1",
                    name = "echo",
                    input = "raw",
                ),
            ).first,
        )
    }

    test("decodes json payload and returns plain text") {
        val tool = textTool(
            spec = jsonToolTestSpec,
            inputDeserializer = JsonToolInput.serializer(),
        ) { input ->
            jsonToolSuccess("echoed: ${input.value}")
        }

        assertEquals(
            ResponseItem.FunctionCallOutput(
                callId = "call_1",
                output = FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text("echoed: hello"),
                    success = true,
                ),
            ),
            tool.handle(
                ResponseItem.FunctionCall(
                    name = "echo",
                    arguments = """{"value":"hello"}""",
                    callId = "call_1",
                ),
            ).first,
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

    test("returns protocol-native rich function output") {
        val tool = functionOutputTool(
            spec = jsonToolTestSpec,
            inputDeserializer = JsonToolInput.serializer(),
        ) { callId, input ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.ContentItems(
                    listOf(
                        FunctionCallOutputContentItem.InputImage(
                            imageUrl = "data:image/png;base64,${input.value}",
                            detail = ImageDetail.High,
                        ),
                        FunctionCallOutputContentItem.InputText("saved from $callId"),
                    ),
                ),
                success = true,
            ) to StableTextToolEvent(
                name = "echo",
                arguments = JsonPrimitive(input.value),
                result = "saved from $callId",
                success = true,
            )
        }

        assertEquals(
            ResponseItem.FunctionCallOutput(
                callId = "call_1",
                output = FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.ContentItems(
                        listOf(
                            FunctionCallOutputContentItem.InputImage(
                                imageUrl = "data:image/png;base64,BASE64",
                                detail = ImageDetail.High,
                            ),
                            FunctionCallOutputContentItem.InputText("saved from call_1"),
                        ),
                    ),
                    success = true,
                ),
            ),
            tool.handle(
                ResponseItem.FunctionCall(
                    name = "echo",
                    arguments = """{"value":"BASE64"}""",
                    callId = "call_1",
                ),
            ).first,
        )
    }
}
