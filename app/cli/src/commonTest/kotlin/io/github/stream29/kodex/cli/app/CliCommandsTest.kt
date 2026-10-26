package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.*

val cliCommandsTest by testSuite {
    test("valid commands select exactly one runtime invocation after parsing") {
        val cases = listOf(
            emptyList<String>() to CliInvocation.Tui,
            listOf("web") to CliInvocation.Web,
            listOf("app") to CliInvocation.App,
            listOf("backend", "start") to CliInvocation.BackendStart(emptySet()),
        ) + BackendControlCommand.entries.map {
            listOf("backend", it.commandName) to CliInvocation.BackendControl(it)
        }
        for ((arguments, expected) in cases) {
            var actions = 0
            val code = dispatchCli(arguments, { _, _ -> fail("Unexpected output for $arguments") }) {
                actions++
                assertEquals(expected, it)
                17
            }
            assertEquals(17, code)
            assertEquals(1, actions)
        }
    }

    test("help at every level and legacy help never enter the runtime boundary") {
        val paths = listOf(emptyList(), listOf("web"), listOf("app"), listOf("backend")) +
            listOf("start", "status", "stop", "reload").map { listOf("backend", it) }
        val cases = paths.flatMap { path -> listOf(path + "-h", path + "--help") } +
            listOf(listOf("help"), listOf("backend"), listOf("backend", "start", "--web-origin", "bad", "--help"))
        for (arguments in cases) {
            val messages = mutableListOf<String>()
            val code = dispatchCli(arguments, { message, error ->
                assertFalse(error)
                messages += message
            }) { fail("Help must not resolve Home, log, migrate, discover/start backend or open UI: $arguments") }
            assertEquals(0, code, arguments.toString())
            assertTrue(messages.single().startsWith("Usage: kodex"))
            assertContains(messages.single(), "--help")
            assertContains(messages.single(), "-h")
        }
    }

    test("invalid arguments use stderr and nonzero status without entering runtime") {
        val cases = listOf(
            listOf("--unknown"), listOf("unknown"), listOf("web", "extra"), listOf("app", "--unknown"),
            listOf("backend", "unknown"), listOf("backend", "--web-origin=http://localhost", "start"),
            listOf("backend", "start", "--web-origin"), listOf("backend", "start", "--web-origin="),
            listOf("backend", "start", "--unknown"), listOf("backend", "start", "extra"),
            listOf("backend", "start", "--web-origin", "--unknown"),
            listOf("backend", "start", "--web-origin=http://remote.test:8080"),
            listOf("backend", "start", "--web-origin=http://localhost:65536"),
            listOf("backend", "start", "--web-origin=http://localhost/path"),
            listOf("backend", "status", "--web-origin=http://localhost"),
            listOf("backend", "stop", "extra"), listOf("backend", "reload", "--unknown"),
            listOf("@not-an-argument-file"),
        )
        for (arguments in cases) {
            val messages = mutableListOf<String>()
            val code = dispatchCli(arguments, { message, error ->
                assertTrue(error)
                messages += message
            }) { fail("Invalid input must not enter runtime: $arguments") }
            assertNotEquals(0, code, arguments.toString())
            assertContains(messages.single(), "Usage: kodex")
            assertContains(messages.single(), "Error:")
        }
    }

    test("framework handles repeated and equals options before canonical deduplication") {
        val arguments = listOf(
            "backend", "start", "--web-origin", "http://127.0.0.1:8080",
            "--web-origin=http://localhost:80", "--web-origin", "http://localhost",
        )
        assertEquals(0, dispatchCli(arguments, { _, _ -> fail("Unexpected output") }) {
            assertEquals(CliInvocation.BackendStart(setOf("http://127.0.0.1:8080", "http://localhost")), it)
            0
        })
    }

    test("runtime failures propagate rather than becoming parser errors") {
        val failure = IllegalStateException("runtime failed")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            dispatchCli(listOf("web"), { _, _ -> fail("Unexpected parser output") }) { throw failure }
        })
    }
}
