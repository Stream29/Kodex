package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.tool.intentname.isValidIntentName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val commandIntentNameTest by testSuite {
    test("new input is nonblank single-line text with no extra length limit") {
        for (name in listOf("  检查编译 🧪  ", "a".repeat(10_000))) {
            assertTrue(name.isValidIntentName())
        }
        for (name in listOf("", " \u00a0 ", "one\ttwo", "one\r\ntwo", "one\u007f", "one\u009b", "one\u2028", "one\u2029")) {
            assertFalse(name.isValidIntentName())
        }
    }

    test("pending and completed calls and exit events require a valid name when decoding") {
        val json = Json
        fun actionWithName(nameFragment: String): String {
            val arguments = """{"cmd":"pwd","yield_time_ms":10$nameFragment}"""
            return """{"type":"exec_command","arguments":$arguments}"""
        }
        fun decodePending(nameFragment: String) = json.decodeFromString<PendingCommandExecution>(
            """{"call_id":"call","action":${actionWithName(nameFragment)}}""",
        )
        fun decodeCompleted(nameFragment: String) = json.decodeFromString<StableCommandExecution>(
            """{"call_id":"call","action":${actionWithName(nameFragment)},"result":{"type":"failure","message":"fixture"}}""",
        )
        fun decodeExit(nameFragment: String) = json.decodeFromString<StableTerminalExitedEvent>(
            """{"session_id":2,"command":"pwd","exit_code":7$nameFragment}""",
        )
        val invalidFragments = listOf("", ""","name":null""") +
            listOf("", "   ", "检查\n输出 🧪").map { ""","name":${JsonPrimitive(it)}""" }
        for (invalid in invalidFragments) {
            assertFailsWith<IllegalArgumentException> { decodePending(invalid) }
            assertFailsWith<IllegalArgumentException> { decodeCompleted(invalid) }
            assertFailsWith<IllegalArgumentException> { decodeExit(invalid) }
        }
        val expected = "  检查编译 🧪  "
        val valid = ""","name":${JsonPrimitive(expected)}"""
        assertEquals(expected, (decodePending(valid).action as PendingCommandExecutionAction.ExecCommand).arguments.name)
        assertEquals(expected, (decodeCompleted(valid).action as StableCommandExecutionAction.ExecCommand).arguments.name)
        val exited = decodeExit(valid)
        assertEquals(expected, exited.name)
        assertEquals(exited, json.decodeFromString<StableTerminalExitedEvent>(
            json.encodeToString(StableTerminalExitedEvent.serializer(), exited),
        ))
    }
}
