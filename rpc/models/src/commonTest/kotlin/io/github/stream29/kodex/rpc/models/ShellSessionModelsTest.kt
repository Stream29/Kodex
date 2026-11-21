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
            name = "Run the fixture 🧪",
            workdir = "/fixture/work",
            shell = Shell(ShellType.Bash, Path("/fixture/bin/bash")),
            yieldTimeMillis = 1234,
            maxOutputTokens = 5678,
        )
        val snapshot = linkedMapOf(
            7 to ShellSessionState(arguments = arguments, registrationNonce = "registration-a", exitCode = null),
            Int.MAX_VALUE to ShellSessionState(
                arguments = ExecCommandArguments(command = "completed fixture", name = "Run completed fixture", yieldTimeMillis = 10_000),
                registrationNonce = "registration-b",
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
            arguments = ExecCommandArguments(command = "fixture", name = "Run a fixture", yieldTimeMillis = 10_000),
            registrationNonce = "registration-a",
            exitCode = null,
        )
        val completed = running.copy(exitCode = 0)
        val before = mapOf(1 to running)
        val after = mapOf(1 to completed)
        assertEquals(before.keys, after.keys)
        assertNotEquals(before, after)
        assertNotEquals(after, emptyMap<Int, ShellSessionState>())
        val fields = json.encodeToJsonElement(ShellSessionState.serializer(), completed).jsonObject
        assertEquals(setOf("arguments", "registrationNonce", "exitCode"), fields.keys)
        assertTrue(json.decodeFromString<ShellSessionState>(fields.toString()).completed)
        assertEquals(running.registrationNonce, completed.registrationNonce)
        assertNotEquals(before, mapOf(1 to running.copy(registrationNonce = "registration-b")))
    }

    test("arguments include a required intent name; registration nonce is required but exitCode can be absent") {
        val decoded = json.decodeFromString<ShellSessionState>(
            """{"arguments":{"cmd":"fixture","name":"Run a fixture","yield_time_ms":10000},"registrationNonce":"registration-a"}""",
        )
        assertEquals(
            ShellSessionState(
                arguments = ExecCommandArguments(command = "fixture", name = "Run a fixture", yieldTimeMillis = 10_000),
                registrationNonce = "registration-a",
            ),
            decoded,
        )
        for (invalid in listOf(
            """{"arguments":{"cmd":"fixture","name":"Run a fixture","yield_time_ms":10000}}""",
            """{"arguments":{"cmd":"fixture","yield_time_ms":10000},"registrationNonce":"registration-a"}""",
            """{"arguments":{"cmd":"fixture","name":null,"yield_time_ms":10000},"registrationNonce":"registration-a"}""",
            """{"arguments":{"cmd":"fixture","name":"Run a fixture"},"registrationNonce":"registration-a"}""",
            """{"arguments":{"cmd":"fixture","name":"Run a fixture","yield_time_ms":null},"registrationNonce":"registration-a"}""",
            """{"arguments":{"cmd":"fixture","name":"Run a fixture","yield_time_ms":"not-a-number"},"registrationNonce":"registration-a"}""",
            """{}""",
            """{"arguments":null,"registrationNonce":"registration-a"}""",
            """{"arguments":{"cmd":"fixture","name":"Run a fixture","yield_time_ms":10000},"registrationNonce":"registration-a","exitCode":"not-an-int"}""",
        )) {
            assertFailsWith<SerializationException> {
                json.decodeFromString<ShellSessionState>(invalid)
            }
        }
    }
}
