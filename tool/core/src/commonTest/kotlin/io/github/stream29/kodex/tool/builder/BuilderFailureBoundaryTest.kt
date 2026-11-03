package io.github.stream29.kodex.tool.builder

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.openai.ResponsesApiTool
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.assertEquals
import kotlin.test.assertIs

@Serializable
private data class FailureInput(val value: String)

private val failureSpec = ResponsesApiTool(
    name = "failure",
    description = "Tests failure handling.",
    parameters = PropertyBuilder().obj {
        property("value") {
            required = true
            string { description = "Input value." }
        }
    },
)

private fun failurePending() = PendingFunctionToolEvent(
    name = "failure",
    callId = "builder-call",
    arguments = ToolBuilderJson.parseToJsonElement("""{"value":"sample"}"""),
)

val builderFailureBoundaryTest by testSuite {
    test("malformed JSON input returns a failed completion") {
        val tool = textTool(failureSpec, FailureInput.serializer()) { jsonToolSuccess(it.value) }
        val pending = failurePending().copy(arguments = ToolBuilderJson.parseToJsonElement("{}"))
        val event = assertIs<StableTextToolEvent>(tool.handle(pending))
        assertEquals(false, event.success)
        assertEquals(pending.callId, event.callId)
    }

    test("unexpected input decoder failure is sanitized") {
        val decoder = object : KSerializer<FailureInput> {
            override val descriptor = FailureInput.serializer().descriptor
            override fun deserialize(decoder: Decoder): FailureInput = error("private decoder message")
            override fun serialize(encoder: Encoder, value: FailureInput) = error("not used")
        }
        val tool = textTool(failureSpec, decoder) { jsonToolSuccess(it.value) }
        val event = assertIs<StableTextToolEvent>(tool.handle(failurePending()))
        assertEquals(false, event.success)
        assertEquals("Tool execution failed.", event.result)
    }

    test("unexpected output serializer failure is sanitized") {
        val serializer = object : KSerializer<String> {
            override val descriptor = String.serializer().descriptor
            override fun deserialize(decoder: Decoder): String = error("not used")
            override fun serialize(encoder: Encoder, value: String) = error("private serializer message")
        }
        val tool = jsonTool(failureSpec, FailureInput.serializer(), serializer) { jsonToolSuccess(it.value) }
        val event = assertIs<StableTextToolEvent>(tool.handle(failurePending()))
        assertEquals(false, event.success)
        assertEquals("Tool execution failed.", event.result)
    }

    test("unexpected handler exception returns a safe failure completion") {
        val tool = textTool(failureSpec, FailureInput.serializer()) {
            error("private-handler-message")
        }
        val event = assertIs<StableTextToolEvent>(tool.handle(failurePending()))
        assertEquals("builder-call", event.callId)
        assertEquals(false, event.success)
        assertEquals("Tool execution failed.", event.result)
    }

    test("completion projection exception returns a safe failure completion") {
        val tool = textTool(
            spec = failureSpec,
            inputDeserializer = FailureInput.serializer(),
            completedEvent = { _, _, _ -> error("private-projection-message") },
        ) { jsonToolSuccess("success") }
        val event = assertIs<StableTextToolEvent>(tool.handle(failurePending()))
        assertEquals("builder-call", event.callId)
        assertEquals(false, event.success)
        assertEquals("Tool execution failed.", event.result)
    }
}
