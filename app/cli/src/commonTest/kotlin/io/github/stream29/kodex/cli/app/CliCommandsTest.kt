package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.*

val cliCommandsTest by testSuite {
    test("host help calls the pairing service a frontend server") {
        val messages = mutableListOf<String>()
        assertEquals(0, dispatchCli(listOf("host", "--help"), { message, error ->
            assertFalse(error)
            messages += message
        }) { fail("Help must not start a frontend server") })
        assertContains(messages.single(), "frontend server")
        assertFalse(messages.single().contains("companion", ignoreCase = true))
    }

    test("app accepts only the explicit Chrome and system engine options") {
        for (engine in listOf("chrome", "system")) {
            var calls = 0
            val output = mutableListOf<String>()
            val status = dispatchCli(listOf("app", "--engine=$engine"), { message, _ -> output += message }) {
                calls++
                0
            }
            assertEquals(0, status, "Supported app engine $engine must parse: $output")
            assertEquals(1, calls)
        }
        for (arguments in listOf(listOf("app", "--engine=unknown"), listOf("web", "--engine=chrome"))) {
            assertNotEquals(0, dispatchCli(arguments, { _, _ -> }) { fail("Invalid engine must not enter runtime") })
        }
    }
    test("valid commands select exactly one runtime invocation after parsing") {
        val cases = listOf(
            listOf("tui") to CliInvocation.Tui(),
            listOf("tui", "--backend-url", "http://127.0.0.1:18080") to
                CliInvocation.Tui(CliBackendTarget(18080)),
            listOf("tui", "--backend-url=http://localhost:18081", "--backend-home=/tmp/kodex-other") to
                CliInvocation.Tui(CliBackendTarget(18081, "/tmp/kodex-other")),
            listOf("web") to CliInvocation.Web(),
            listOf("web", "--backend-url", "http://127.0.0.1:18080") to
                CliInvocation.Web(backend = CliBackendTarget(18080)),
            listOf("web", "--origin", "http://localhost:18795") to
                CliInvocation.Web(origin = "http://localhost:18795"),
            listOf(
                "web", "--origin=http://127.0.0.1:18795",
                "--backend-url=http://localhost:18082", "--backend-home", "/tmp/kodex-web-other",
            ) to CliInvocation.Web(
                origin = "http://127.0.0.1:18795",
                backend = CliBackendTarget(18082, "/tmp/kodex-web-other"),
            ),
            listOf("app") to CliInvocation.App(),
            listOf("app", "--engine=chrome") to CliInvocation.App(CliAppEngine.Chrome),
            listOf("app", "--engine=system") to CliInvocation.App(CliAppEngine.System),
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

    test("legacy host command emits migration notice on stderr and forwards to web --origin") {
        val cases = listOf(
            listOf("host", "--origin", "http://localhost:18795") to
                CliInvocation.Web(origin = "http://localhost:18795"),
            listOf(
                "host", "--origin=http://127.0.0.1:18795",
                "--backend-url", "http://localhost:18080", "--backend-home=/tmp/kodex-host-other",
            ) to CliInvocation.Web(
                origin = "http://127.0.0.1:18795",
                backend = CliBackendTarget(18080, "/tmp/kodex-host-other"),
            ),
        )
        for ((arguments, expected) in cases) {
            val notices = mutableListOf<Pair<String, Boolean>>()
            var actions = 0
            val code = dispatchCli(arguments, { message, error -> notices += message to error }) {
                actions++
                assertEquals(expected, it)
                23
            }
            assertEquals(23, code)
            assertEquals(1, actions)
            assertEquals(listOf(HostCommandMigrationNotice to true), notices)
        }
    }

    test("help at every level and empty root never enter the runtime boundary") {
        val paths = listOf(emptyList(), listOf("tui"), listOf("web"), listOf("app"), listOf("host"), listOf("backend")) +
            listOf("start", "status", "stop", "reload").map { listOf("backend", it) }
        val cases = paths.flatMap { path -> listOf(path + "-h", path + "--help") } +
            listOf(emptyList(), listOf("backend"), listOf("backend", "start", "--web-origin", "bad", "--help"))
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
        val invalidBackendUrls = listOf(
            "", "http://localhost", "http://127.0.0.1", "https://127.0.0.1:8080", "ws://127.0.0.1:8080",
            "http://remote.test:8080", "http://[::1]:8080", "http://user:pass@127.0.0.1:8080",
            "http://127.0.0.1:8080/", "http://127.0.0.1:8080/rpc", "http://127.0.0.1:8080?x=1",
            "http://127.0.0.1:8080#frag", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:080",
        )
        val cases = listOf(
            listOf("--unknown"), listOf("unknown"), listOf("help"), listOf("tui", "extra"),
            listOf("tui", "--unknown"), listOf("tui", "--origin", "http://localhost:18795"),
            listOf("tui", "--backend-home", "/tmp/only-home"),
            listOf("tui", "--backend-url", "http://127.0.0.1:8080", "--backend-home", "   "),
            listOf("web", "extra"), listOf("web", "--backend-home", "/tmp/only-home"),
            listOf("web", "--origin", "http://localhost:18795", "--backend-home", "/tmp/only-home"),
            listOf("web", "--origin", "http://remote.test"),
            listOf("web", "--origin", "http://localhost/path"),
            listOf("app", "--unknown"), listOf("app", "--backend-url", "http://127.0.0.1:8080"),
            listOf("host"), listOf("host", "--origin"), listOf("host", "--origin", "http://remote.test"),
            listOf("host", "--origin", "http://localhost/path"),
            listOf("host", "--origin", "http://localhost:18795", "--backend-home", "/tmp/only-home"),
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
        ) + invalidBackendUrls.flatMap { badUrl ->
            listOf(
                listOf("tui", "--backend-url", badUrl),
                listOf("web", "--backend-url", badUrl),
                listOf("host", "--origin", "http://localhost:18795", "--backend-url", badUrl),
            )
        }
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

    test("root help names the tui subcommand without advertising the removed alias") {
        var text: String? = null
        assertEquals(0, dispatchCli(emptyList(), { message, error ->
            assertFalse(error)
            text = message
        }) { fail("No command must not start a frontend") })
        assertContains(assertNotNull(text), "tui")
        assertFalse(assertNotNull(text).contains("alias"))
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
