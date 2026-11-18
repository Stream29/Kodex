package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.tool.unifiedexec.commandIntentDisplayName
import io.github.stream29.kodex.tool.unifiedexec.isValidCommandIntentName
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val commandIntentNameTest by testSuite {
    test("new input is nonblank single-line text with no extra length limit") {
        for (name in listOf("  检查编译 🧪  ", "a".repeat(10_000))) {
            assertTrue(name.isValidCommandIntentName())
            assertEquals(name.trim(), name.commandIntentDisplayName())
        }
        for (name in listOf(null, "", " \u00a0 ", "one\ttwo", "one\r\ntwo", "one\u007f", "one\u009b", "one\u2028", "one\u2029")) {
            assertFalse(name.isValidCommandIntentName())
        }
        assertEquals("one two three", "\tone\ntwo\u0085three\u2028".commandIntentDisplayName())
        assertEquals("", "\u0000 \u0085\u2029".commandIntentDisplayName())
    }

    test("historical pending and completed calls and exit events keep the original nullable name") {
        val json = Json
        for ((nameFragment, expected) in listOf(
            "" to null, ",\"name\":null" to null, ",\"name\":\"\"" to "",
            ",\"name\":\"  \"" to "  ", ",\"name\":\"检查\\n输出 🧪\"" to "检查\n输出 🧪",
        )) {
            val arguments = """{"cmd":"pwd","yield_time_ms":10$nameFragment}"""
            val action = """{"type":"exec_command","arguments":$arguments}"""
            val pending = json.decodeFromString<PendingCommandExecution>("""{"call_id":"call","action":$action}""")
            val completed = json.decodeFromString<StableCommandExecution>(
                """{"call_id":"call","action":$action,"result":{"type":"failure","message":"fixture"}}""",
            )
            val exited = json.decodeFromString<StableTerminalExitedEvent>(
                """{"session_id":2,"command":"pwd","exit_code":7$nameFragment}""",
            )
            assertEquals(expected, (pending.action as PendingCommandExecutionAction.ExecCommand).arguments.name)
            assertEquals(expected, (completed.action as StableCommandExecutionAction.ExecCommand).arguments.name)
            assertEquals(expected, exited.name)
            assertEquals(exited, json.decodeFromString<StableTerminalExitedEvent>(
                json.encodeToString(StableTerminalExitedEvent.serializer(), exited),
            ))
        }
    }
}
