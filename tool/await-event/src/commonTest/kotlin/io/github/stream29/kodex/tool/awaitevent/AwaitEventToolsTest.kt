package io.github.stream29.kodex.tool.awaitevent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

val awaitEventToolsTest by testSuite {
    test("serialized await_event schema requires a nonempty array of closed tagged variants") {
        val spec = AwaitEventTools.spec
        assertEquals("await_event", AwaitEventTools.Name)
        assertEquals(AwaitEventTools.Name, spec.name)
        assertEquals(AwaitEventTools.Description, spec.description)
        assertFalse(spec.strict)
        val schema = Json.encodeToJsonElement(ObjectPropertyDefinition.serializer(), spec.parameters).jsonObject
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(JsonArray(listOf(JsonPrimitive("events"))), schema["required"])
        val events = schema.getValue("properties").jsonObject.getValue("events").jsonObject
        assertEquals(JsonPrimitive(1), events["minItems"])
        val branches = assertIs<JsonArray>(events.getValue("items").jsonObject.getValue("oneOf"))
        val fieldsByType = branches.associate { branch ->
            val obj = assertIs<JsonObject>(branch)
            assertEquals(JsonPrimitive(false), obj["additionalProperties"])
            val fields = obj.getValue("properties").jsonObject
            val type = assertIs<JsonPrimitive>(
                assertIs<JsonArray>(fields.getValue("type").jsonObject.getValue("enum")).single(),
            ).content
            assertEquals(fields.keys.map(::JsonPrimitive).toSet(), assertIs<JsonArray>(obj["required"]).toSet())
            type to (fields.keys - "type")
        }
        assertEquals(
            mapOf(
                "user_message" to emptySet(),
                "ask_user_answered" to emptySet(),
                "schedule_triggered" to emptySet(),
                "terminal_exited" to setOf("session_id"),
                "timeout" to setOf("duration_ms"),
            ),
            fieldsByType,
        )
    }

    test("all five branches round trip; timeout anchors the shortest delay") {
        val inputs = AwaitEventArguments(listOf(
            AwaitEventInput.UserMessage,
            AwaitEventInput.AskUserAnswered,
            AwaitEventInput.ScheduleTriggered,
            AwaitEventInput.TerminalExited(11),
            AwaitEventInput.TerminalExited(11),
            AwaitEventInput.Timeout(5000),
            AwaitEventInput.Timeout(250),
        ))
        val encoded = OpenAiJsonCodec.encodeToString(AwaitEventArguments.serializer(), inputs)
        assertEquals(inputs, decodeAwaitEventArguments(encoded))
        assertEquals(setOf(11), inputs.terminalIds)
        val started = Instant.parse("2026-11-18T00:00:00.001Z")
        assertEquals(started + 250.milliseconds, inputs.timeoutAt(started))
        assertNull(AwaitEventArguments(listOf(AwaitEventInput.UserMessage)).timeoutAt(started))
        assertFailsWith<IllegalArgumentException> {
            inputs.timeoutAt(Instant.fromEpochSeconds(Long.MAX_VALUE) - 1.milliseconds)
        }
    }

    test("strict model input rejects legacy, empty or malformed variants") {
        for (input in listOf(
            "{}",
            """{"terminals":[11]}""",
            """{"events":[]}""",
            """{"events":null}""",
            """{"events":[{"type":"schedule_triggered"}],"terminals":[]}""",
            """{"events":[{"type":"schedule_triggered","id":2}]}""",
            """{"events":[{}]}""",
            """{"events":[{"type":"unknown"}]}""",
            """{"events":[{"type":"terminal_exited"}]}""",
            """{"events":[{"type":"terminal_exited","session_id":true}]}""",
            """{"events":[{"type":"terminal_exited","session_id":"11"}]}""",
            """{"events":[{"type":"terminal_exited","session_id":2147483648}]}""",
            """{"events":[{"type":"timeout","duration_ms":0}]}""",
            """{"events":[{"type":"timeout","duration_ms":-1}]}""",
            """{"events":[{"type":"timeout","duration_ms":0.01}]}""",
            """{"events":[{"type":"timeout","duration_ms":9223372036854775807}]}""",
        )) {
            assertFailsWith<IllegalArgumentException>(input) { decodeAwaitEventArguments(input) }
        }
    }

    test("AwaitEventOutput round-trips instant timestamp through JSON") {
        val instant = Instant.fromEpochMilliseconds(1_762_000_123_456L)
        val output = AwaitEventOutput(timestamp = instant)
        val encoded = OpenAiJsonCodec.encodeToString(AwaitEventOutput.serializer(), output)
        assertEquals(output, OpenAiJsonCodec.decodeFromString(AwaitEventOutput.serializer(), encoded))
    }
}
