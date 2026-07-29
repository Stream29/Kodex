package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

private val stableEventJson = Json

val stableCleanEventSerializationTest by testSuite {
    test("round trips a completed event through stable clean event") {
        val event: StableCleanEvent = StableAssistantMessage("done")

        val encoded = stableEventJson.encodeToString(event)
        val element = stableEventJson.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("assistant_message"), element["type"])
        assertEquals(event, stableEventJson.decodeFromString<StableCleanEvent>(encoded))
    }
}
