@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val notificationHookSpecTest by testSuite {
    test("the moved declaration preserves its original descriptor and enum wire values") {
        assertEquals("io.github.stream29.kodex.rpc.models.NotificationHook", NotificationHook.serializer().descriptor.serialName)
        assertEquals(
            "io.github.stream29.kodex.rpc.models.NotificationHookType",
            NotificationHookType.serializer().descriptor.serialName,
        )
        assertEquals(
            listOf("\"stop_assistant_message\"", "\"stop_request_user_input\"", "\"stop_suggest_subagent\"", "\"stop_unhandled_error\""),
            NotificationHookType.entries.map { Json.encodeToString(it) },
        )
        val original = NotificationHook(" keep spaces ", NotificationHookType.entries.toSet(), " literal command ")
        assertEquals(original, Json.decodeFromString<NotificationHook>(Json.encodeToString(original)))
    }

    test("the authoritative value rejects blank names commands and empty selections") {
        val selected = setOf(NotificationHookType.StopAssistantMessage)
        assertFailsWith<IllegalArgumentException> { NotificationHook(" ", selected, "literal") }
        assertFailsWith<IllegalArgumentException> { NotificationHook("name", selected, "\n") }
        assertFailsWith<IllegalArgumentException> { NotificationHook("name", emptySet(), "literal") }
        assertEquals(
            NotificationHook("name", selected, "literal"),
            Json.decodeFromString<NotificationHook>(
                """{"name":"name","types":["stop_assistant_message","stop_assistant_message"],"command":"literal"}""",
            ),
        )
    }
}
