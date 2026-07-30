package io.github.stream29.kodex.tool.builder

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponsesApiTool
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.Serializable
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
            StableJsonToolEvent(
                callId = "call_1",
                name = "echo",
                arguments = ToolBuilderJson.parseToJsonElement("""{"value":"hello"}"""),
                result = ToolBuilderJson.encodeToJsonElement(
                    JsonToolOutput.serializer(),
                    JsonToolOutput("hello"),
                ),
                success = true,
            ),
            tool.handle(
                PendingFunctionToolEvent(
                    name = "echo",
                    arguments = ToolBuilderJson.parseToJsonElement("""{"value":"hello"}"""),
                    callId = "call_1",
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
            StableCustomToolEvent(
                callId = "call_1",
                name = "echo",
                input = "raw",
                result = FunctionCallOutputPayload(
                    body = FunctionCallOutputBody.Text("JSON tool received custom tool payload"),
                    success = false,
                ),
                success = false,
            ),
            tool.handle(
                PendingCustomToolEvent(
                    callId = "call_1",
                    name = "echo",
                    input = "raw",
                ),
            ),
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
            StableTextToolEvent(
                callId = "call_1",
                name = "echo",
                arguments = ToolBuilderJson.parseToJsonElement("""{"value":"hello"}"""),
                result = "echoed: hello",
                success = true,
            ),
            tool.handle(
                PendingFunctionToolEvent(
                    name = "echo",
                    arguments = ToolBuilderJson.parseToJsonElement("""{"value":"hello"}"""),
                    callId = "call_1",
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

    test("returns the handler's clean completed event") {
        val tool = functionOutputTool(
            spec = jsonToolTestSpec,
            inputDeserializer = JsonToolInput.serializer(),
        ) { pending, input ->
            StableTextToolEvent(
                callId = pending.callId,
                itemId = pending.itemId,
                name = pending.name,
                namespace = pending.namespace,
                arguments = ToolBuilderJson.parseToJsonElement("""{"value":"${input.value}"}"""),
                result = "saved from ${pending.callId}",
                success = true,
            )
        }

        assertEquals(
            StableTextToolEvent(
                callId = "call_1",
                name = "echo",
                arguments = ToolBuilderJson.parseToJsonElement("""{"value":"BASE64"}"""),
                result = "saved from call_1",
                success = true,
            ),
            tool.handle(
                PendingFunctionToolEvent(
                    name = "echo",
                    arguments = ToolBuilderJson.parseToJsonElement("""{"value":"BASE64"}"""),
                    callId = "call_1",
                ),
            ),
        )
    }
}
