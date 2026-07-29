package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals

private val pendingEventJson = Json

val pendingToolEventSerializationTest by testSuite {
    test("round trips call identity with a pending invocation") {
        val event = PendingToolEvent(
            callId = "call_pending",
            invocation = PendingToolInvocation.Function(
                name = "search",
                namespace = "google_drive",
                arguments = PendingFunctionArguments.Json(
                    buildJsonObject {
                        put("query", "design context")
                    },
                ),
            ),
        )

        val encoded = pendingEventJson.encodeToString(event)
        val element = pendingEventJson.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("call_pending"), element["call_id"])
        assertEquals(
            JsonPrimitive("function"),
            element["invocation"]?.jsonObject?.get("type"),
        )
        assertEquals(event, pendingEventJson.decodeFromString<PendingToolEvent>(encoded))
    }
}
