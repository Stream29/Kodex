package io.github.stream29.codex.lite.agentstorage.cleanmodels

import de.infix.testBalloon.framework.core.testSuite

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

private val json = Json

val cleanHistorySerializationTest by testSuite {
    test("round trips stable clean event") {
        val event: StableCleanEvent = StableAssistantMessage("done")

        val encoded = json.encodeToString(event)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("assistant_message"), element["type"])
        assertEquals(event, json.decodeFromString<StableCleanEvent>(encoded))
    }

    test("round trips unstable clean tail") {
        val tail = UnstableCleanTail(
            items = listOf(
                UnstableCleanEvent.PendingToolCall(
                    name = "apply_patch",
                    kind = PendingToolCallKind.Custom,
                    input = "*** Begin Patch\n*** End Patch\n",
                ),
                UnstableCleanEvent.PendingContextCompaction,
            ),
        )

        val encoded = json.encodeToString(tail)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("pending_tool_call"), element.getValue("items").jsonArray[0].jsonObject["type"])
        assertEquals(JsonPrimitive("custom"), element.getValue("items").jsonArray[0].jsonObject["kind"])
        assertEquals(JsonPrimitive("pending_context_compaction"), element.getValue("items").jsonArray[1].jsonObject["type"])
        assertEquals(tail, json.decodeFromString<UnstableCleanTail>(encoded))
    }
}
