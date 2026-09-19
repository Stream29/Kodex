package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val shellSessionModelsTest by testSuite {
    val json = Json { encodeDefaults = true }

    test("registry snapshots retain original arguments and completed entries") {
        val arguments = ExecCommandArguments(
            command = "fixture command\nsecond line",
            workdir = "/fixture/work",
            shell = Shell(ShellType.Bash, Path("/fixture/bin/bash")),
            tty = true,
            yieldTimeMillis = 1234,
            maxOutputTokens = 5678,
        )
        val snapshot = linkedMapOf(
            7 to ShellSessionState(arguments, completed = false),
            Int.MAX_VALUE to ShellSessionState(
                ExecCommandArguments(command = "completed fixture"),
                completed = true,
            ),
        )
        val encoded = json.encodeToString<Map<Int, ShellSessionState>>(snapshot)
        assertEquals(snapshot, json.decodeFromString<Map<Int, ShellSessionState>>(encoded))
        val empty = emptyMap<Int, ShellSessionState>()
        assertEquals(empty, json.decodeFromString<Map<Int, ShellSessionState>>(json.encodeToString(empty)))
    }

    test("completion changes the value without changing registry membership") {
        val running = ShellSessionState(ExecCommandArguments(command = "fixture"), false)
        val completed = running.copy(completed = true)
        val before = mapOf(1 to running)
        val after = mapOf(1 to completed)
        assertEquals(before.keys, after.keys)
        assertNotEquals(before, after)
        assertNotEquals(after, emptyMap<Int, ShellSessionState>())
        val fields = json.encodeToJsonElement(ShellSessionState.serializer(), completed).jsonObject
        assertEquals(setOf("arguments", "completed"), fields.keys)
        assertTrue(json.decodeFromString<ShellSessionState>(fields.toString()).completed)
    }

    test("completion is required and original argument defaults remain available") {
        val decoded = json.decodeFromString<ShellSessionState>(
            """{"arguments":{"cmd":"fixture"},"completed":false}""",
        )
        assertEquals(ShellSessionState(ExecCommandArguments(command = "fixture"), false), decoded)
        for (invalid in listOf(
            """{"arguments":{"cmd":"fixture"}}""",
            """{"arguments":{"cmd":"fixture"},"completed":null}""",
            """{"completed":true}""",
        )) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<ShellSessionState>(invalid)
            }
        }
    }
}
