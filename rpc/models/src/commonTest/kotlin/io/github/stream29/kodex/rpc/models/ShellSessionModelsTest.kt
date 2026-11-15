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
            yieldTimeMillis = 1234,
            maxOutputTokens = 5678,
        )
        val snapshot = linkedMapOf(
            7 to ShellSessionState(cwd = "/fixture/work", arguments = arguments, exitCode = null),
            Int.MAX_VALUE to ShellSessionState(
                cwd = "/fixture/done",
                arguments = ExecCommandArguments(command = "completed fixture", yieldTimeMillis = 10_000),
                exitCode = 0,
            ),
        )
        val encoded = json.encodeToString<Map<Int, ShellSessionState>>(snapshot)
        assertEquals(snapshot, json.decodeFromString<Map<Int, ShellSessionState>>(encoded))
        val empty = emptyMap<Int, ShellSessionState>()
        assertEquals(empty, json.decodeFromString<Map<Int, ShellSessionState>>(json.encodeToString(empty)))
    }

    test("completion changes the value without changing registry membership") {
        val running = ShellSessionState(
            cwd = "/fixture/work",
            arguments = ExecCommandArguments(command = "fixture", yieldTimeMillis = 10_000),
            exitCode = null,
        )
        val completed = running.copy(exitCode = 0)
        val before = mapOf(1 to running)
        val after = mapOf(1 to completed)
        assertEquals(before.keys, after.keys)
        assertNotEquals(before, after)
        assertNotEquals(after, emptyMap<Int, ShellSessionState>())
        val fields = json.encodeToJsonElement(ShellSessionState.serializer(), completed).jsonObject
        assertEquals(setOf("cwd", "arguments", "exitCode"), fields.keys)
        assertTrue(json.decodeFromString<ShellSessionState>(fields.toString()).completed)
    }

    test("cwd and yield_time_ms are required while optional exitCode and argument defaults remain available") {
        val decoded = json.decodeFromString<ShellSessionState>(
            """{"cwd":"/fixture/work","arguments":{"cmd":"fixture","yield_time_ms":10000}}""",
        )
        assertEquals(
            ShellSessionState(
                cwd = "/fixture/work",
                arguments = ExecCommandArguments(command = "fixture", yieldTimeMillis = 10_000),
            ),
            decoded,
        )
        for (invalid in listOf(
            """{"cwd":"/fixture/work","arguments":{"cmd":"fixture"}}""",
            """{"cwd":"/fixture/work","arguments":{"cmd":"fixture","yield_time_ms":null}}""",
            """{"cwd":"/fixture/work","arguments":{"cmd":"fixture","yield_time_ms":"not-a-number"}}""",
            """{"arguments":{"cmd":"fixture","yield_time_ms":10000}}""",
            """{"cwd":null,"arguments":{"cmd":"fixture","yield_time_ms":10000}}""",
            """{"cwd":"/fixture/work","arguments":{"cmd":"fixture","yield_time_ms":10000},"exitCode":"not-an-int"}""",
            """{"cwd":"/fixture/work"}""",
        )) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<ShellSessionState>(invalid)
            }
        }
    }
}
