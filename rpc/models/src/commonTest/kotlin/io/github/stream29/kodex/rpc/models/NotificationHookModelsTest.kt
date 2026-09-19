package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val notificationHookModelsTest by testSuite {
    val json = Json { encodeDefaults = true }
    val hook = NotificationHook(
        name = "notify",
        types = NotificationHookType.entries.toSet(),
        command = " fixture-notifier --label '通知'\nsecond fixture ",
    )

    test("one Hook round trips all selected notification types and the exact command") {
        val encoded = json.encodeToJsonElement(NotificationHook.serializer(), hook).jsonObject
        assertEquals(setOf("name", "types", "command"), encoded.keys)
        assertEquals(hook.command, encoded.getValue("command").jsonPrimitive.content)
        assertEquals(
            setOf(
                "stop_assistant_message",
                "stop_request_user_input",
                "stop_suggest_subagent",
                "stop_unhandled_error",
            ),
            encoded.getValue("types").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(hook, json.decodeFromJsonElement(NotificationHook.serializer(), encoded))
    }

    test("CLI Hook order is part of settings equality and overlapping commands remain separate") {
        val second = hook.copy(name = "also-notify")
        val settings = CliFrontendSettings(hooks = listOf(hook, second))
        val encoded = json.encodeToString(settings)
        val decoded = json.decodeFromString<CliFrontendSettings>(encoded)
        assertEquals(settings, decoded)
        assertEquals(listOf("notify", "also-notify"), decoded.hooks.map { it.name })
        assertNotEquals(settings, settings.copy(hooks = settings.hooks.reversed()))
        assertEquals(
            settings,
            settings.copy(hooks = listOf(hook.copy(types = hook.types.reversed().toSet()), second)),
        )
    }

    test("missing and empty Hooks preserve CLI defaults while null is rejected") {
        assertEquals(CliFrontendSettings(), json.decodeFromString<CliFrontendSettings>("{}"))
        val empty = json.decodeFromString<CliFrontendSettings>("""{"hooks":[]}""")
        assertTrue(empty.hooks.isEmpty())
        assertEquals(
            0,
            json.encodeToJsonElement(CliFrontendSettings.serializer(), empty)
                .jsonObject.getValue("hooks").jsonArray.size,
        )
        assertFailsWith<SerializationException> {
            json.decodeFromString<CliFrontendSettings>("""{"hooks":null}""")
        }
    }

    test("construction and copy reject blank fields empty selections and duplicate names") {
        assertFailsWith<IllegalArgumentException> { hook.copy(name = " \n\t") }
        assertFailsWith<IllegalArgumentException> { hook.copy(types = emptySet()) }
        assertFailsWith<IllegalArgumentException> { hook.copy(command = " \n\t") }
        assertFailsWith<IllegalArgumentException> {
            CliFrontendSettings(hooks = listOf(hook, hook.copy(command = "other fixture")))
        }
        assertFailsWith<IllegalArgumentException> {
            CliFrontendSettings(hooks = listOf(hook)).copy(hooks = listOf(hook, hook))
        }
    }

    test("decoding enforces Hook validation and unique names") {
        for (invalid in listOf(
            """{"name":" ","types":["stop_assistant_message"],"command":"fixture"}""",
            """{"name":"notify","types":[],"command":"fixture"}""",
            """{"name":"notify","types":["stop_assistant_message"],"command":" "}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                json.decodeFromString<NotificationHook>(invalid)
            }
        }
        val encoded = json.encodeToString(hook)
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<CliFrontendSettings>("""{"hooks":[$encoded,$encoded]}""")
        }
    }

    test("required fields unknown types and the old single type shape are rejected") {
        for (invalid in listOf(
            """{"types":["stop_assistant_message"],"command":"fixture"}""",
            """{"name":"notify","command":"fixture"}""",
            """{"name":"notify","types":["stop_assistant_message"]}""",
            """{"name":null,"types":["stop_assistant_message"],"command":"fixture"}""",
            """{"name":"notify","types":null,"command":"fixture"}""",
            """{"name":"notify","types":["stop_assistant_message"],"command":null}""",
            """{"name":"notify","types":["future_notification"],"command":"fixture"}""",
            """{"name":"notify","types":[null],"command":"fixture"}""",
            """{"name":"notify","type":"stop_assistant_message","command":"fixture"}""",
        )) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<NotificationHook>(invalid)
            }
        }
    }

    test("duplicate type selections decode as one selection") {
        val decoded = json.decodeFromString<NotificationHook>(
            """{"name":"notify","types":["stop_assistant_message","stop_assistant_message"],"command":"fixture"}""",
        )
        assertEquals(setOf(NotificationHookType.StopAssistantMessage), decoded.types)
        assertEquals(
            1,
            json.encodeToJsonElement(NotificationHook.serializer(), decoded)
                .jsonObject.getValue("types").jsonArray.size,
        )
    }
}
