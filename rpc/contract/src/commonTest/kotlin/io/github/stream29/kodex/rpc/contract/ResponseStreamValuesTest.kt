package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

val responseStreamValuesTest by testSuite {
    test("original event serializer preserves replay order deltas and unknown payloads") {
        val serializer = ListSerializer(ResponsesStreamEvent.serializer())
        val events = listOf(
            ResponsesStreamEvent.OutputItemAdded(
                0,
                ResponseItem.Message(role = MessageRole.Assistant, content = emptyList()),
            ),
            ResponsesStreamEvent.OutputTextDelta("message-1", 0, 0, "Hello"),
            ResponsesStreamEvent.OutputTextDelta("message-1", 0, 0, "Hello"),
            ResponsesStreamEvent.Other(
                Json.parseToJsonElement("""{"type":"response.future","extra":{"n":1}}""").jsonObject,
            ),
            ResponsesStreamEvent.OutputItemDone(
                0,
                ResponseItem.Message(
                    role = MessageRole.Assistant,
                    content = listOf(ContentItem.OutputText("HelloHello")),
                ),
            ),
        )
        assertEquals(events, Json.decodeFromString(serializer, Json.encodeToString(serializer, events)))
    }
}
