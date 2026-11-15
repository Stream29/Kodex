package io.github.stream29.kodex.tool.awaitevent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.schema.json.ArrayPropertyDefinition
import kotlinx.schema.json.NumericPropertyDefinition
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

val awaitEventToolsTest by testSuite {
    test("await_event spec exposes optional terminals integer array without extra properties") {
        val spec = AwaitEventTools.spec
        assertEquals("await_event", AwaitEventTools.Name)
        assertEquals(AwaitEventTools.Name, spec.name)
        assertEquals(AwaitEventTools.Description, spec.description)
        assertFalse(spec.strict)
        assertEquals(false, spec.parameters.additionalProperties?.let { it == kotlinx.serialization.json.JsonPrimitive(true) })
        assertNull(spec.parameters.required)

        val terminals = assertIs<ArrayPropertyDefinition>(
            spec.parameters.properties?.get("terminals"),
        )
        assertIs<NumericPropertyDefinition>(terminals.items)
    }

    test("AwaitEventArguments defaults terminals to empty list and round-trips through JSON") {
        val decodedDefault = OpenAiJsonCodec.decodeFromString(AwaitEventArguments.serializer(), "{}")
        assertEquals(AwaitEventArguments(terminals = emptyList()), decodedDefault)

        val withTerminals = AwaitEventArguments(terminals = listOf(11, 29))
        val encoded = OpenAiJsonCodec.encodeToString(AwaitEventArguments.serializer(), withTerminals)
        assertEquals(withTerminals, OpenAiJsonCodec.decodeFromString(AwaitEventArguments.serializer(), encoded))
    }

    test("AwaitEventOutput round-trips instant timestamp through JSON") {
        val instant = Instant.fromEpochMilliseconds(1_762_000_123_456L)
        val output = AwaitEventOutput(timestamp = instant)
        val encoded = OpenAiJsonCodec.encodeToString(AwaitEventOutput.serializer(), output)
        assertEquals(output, OpenAiJsonCodec.decodeFromString(AwaitEventOutput.serializer(), encoded))
    }
}
