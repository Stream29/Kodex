package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

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
        for ((engine, expected) in listOf("chrome" to CliAppEngine.Chrome, "system" to CliAppEngine.System)) {
            var calls = 0
            val output = mutableListOf<String>()
            val status = dispatchCli(listOf("app", "--engine=$engine"), { message, _ -> output += message }) { leaf ->
                calls++
                val app = assertIs<AppCommand>(leaf)
                assertEquals(expected, app.engine)
                0
            }
            assertEquals(0, status, "Supported app engine $engine must parse: $output")
            assertEquals(1, calls)
        }
        for (arguments in listOf(listOf("app", "--engine=unknown"), listOf("web", "--engine=chrome"))) {
            assertNotEquals(0, dispatchCli(arguments, { _, _ -> }) { fail("Invalid engine must not enter runtime") })
        }
    }

    test("valid interactive and backend commands select the exact leaf command after parsing") {
        assertEquals(0, dispatchCli(listOf("tui"), { _, _ -> fail("Unexpected output") }) { leaf ->
            val tui = assertIs<TuiCommand>(leaf)
            assertNull(tui.backend.resolve())
            0
        })
        assertEquals(0, dispatchCli(
            listOf("tui", "--backend-url=http://localhost:18081", "--backend-home=/tmp/kodex-other"),
            { _, _ -> fail("Unexpected output") },
        ) { leaf ->
            val tui = assertIs<TuiCommand>(leaf)
            assertEquals(CliBackendTarget(18081, "/tmp/kodex-other"), tui.backend.resolve())
            0
        })
        assertEquals(0, dispatchCli(
            listOf(
                "web", "--origin=http://127.0.0.1:18795",
                "--backend-url=http://localhost:18082", "--backend-home", "/tmp/kodex-web-other",
            ),
            { _, _ -> fail("Unexpected output") },
        ) { leaf ->
            val web = assertIs<WebCommand>(leaf)
            assertEquals("http://127.0.0.1:18795", web.origin)
            assertEquals(CliBackendTarget(18082, "/tmp/kodex-web-other"), web.backend.resolve())
            0
        })
        for (control in BackendControlCommand.entries) {
            assertEquals(17, dispatchCli(listOf("backend", control.commandName), { _, _ -> fail("Unexpected output") }) { leaf ->
                val cmd = assertIs<BackendControlCliCommand>(leaf)
                assertEquals(control, cmd.control)
                17
            })
        }
    }

    test("legacy host command emits migration notice on stderr and yields FrontendHostCommand") {
        val notices = mutableListOf<Pair<String, Boolean>>()
        var actions = 0
        val code = dispatchCli(
            listOf(
                "host", "--origin=http://127.0.0.1:18795",
                "--backend-url", "http://localhost:18080", "--backend-home=/tmp/kodex-host-other",
            ),
            { message, error -> notices += message to error },
        ) { leaf ->
            actions++
            val host = assertIs<FrontendHostCommand>(leaf)
            assertEquals("http://127.0.0.1:18795", host.origin)
            assertEquals(CliBackendTarget(18080, "/tmp/kodex-host-other"), host.backend.resolve())
            23
        }
        assertEquals(23, code)
        assertEquals(1, actions)
        assertEquals(listOf(HostCommandMigrationNotice to true), notices)
    }

    test("help at every level and empty groups never enter runtime or query MCP domains") {
        val paths = listOf(
            emptyList(),
            listOf("tui"), listOf("web"), listOf("app"), listOf("host"),
            listOf("backend"), listOf("backend", "start"), listOf("backend", "status"),
            listOf("terminal"), listOf("terminal", "read"), listOf("terminal", "write"),
            listOf("ask"), listOf("ask", "create"),
            listOf("schedule"), listOf("schedule", "create"),
            listOf("session"), listOf("session", "status"),
            listOf("search"), listOf("image"),
            listOf("mcp"), listOf("mcp", "status"),
            listOf("tool"), listOf("tool", "list"),
        )
        val cases = paths.flatMap { path -> listOf(path + "-h", path + "--help") } + listOf(
            emptyList(),
            listOf("backend"),
            listOf("terminal"),
            listOf("ask"),
            listOf("schedule"),
            listOf("session"),
            listOf("mcp"),
            listOf("tool"),
            listOf("backend", "start", "--web-origin", "bad", "--help"),
        )
        for (arguments in cases) {
            val messages = mutableListOf<String>()
            val code = dispatchCli(
                arguments = arguments,
                output = { message, error ->
                    assertFalse(error)
                    messages += message
                },
                resolveMcpDomains = { fail("Built-in help/group must not query MCP domains: $arguments") },
                execute = { fail("Help must not enter runtime: $arguments") },
            )
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
            listOf("terminal", "write", "1"),
            listOf("terminal", "write", "1", "hello", "--stdin"),
            listOf("ask", "get", "0"),
            listOf("schedule", "create", "-d", "ping"),
            listOf("schedule", "create", "-d", "ping", "--delay-seconds", "0"),
            listOf("schedule", "create", "-d", "ping", "--cron", "* * * *"),
            listOf("search"),
            listOf("search", "--domain", "kotlinlang.org"),
            listOf("search", "-q", "kotlin", "--stdin"),
            listOf("@not-an-argument-file"),
        ) + invalidBackendUrls.flatMap { badUrl ->
            listOf(
                listOf("tui", "--backend-url", badUrl),
                listOf("web", "--backend-url", badUrl),
                listOf("host", "--origin", "http://localhost:18795", "--backend-url", badUrl),
                listOf("session", "status", "--backend-url", badUrl),
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

    test("dynamic MCP server domains mount at top level and print inputSchema in tool --help while reserved names win") {
        val githubTools = listOf(
            McpCatalogTool(
                serverName = "github",
                serverInstructions = "GitHub repository tools.",
                toolName = "create_issue",
                description = "Create a GitHub issue.",
                inputSchema = ObjectPropertyDefinition(
                    properties = mapOf("title" to StringPropertyDefinition(description = "Issue title")),
                    required = listOf("title"),
                ),
            ),
        )
        val conflictingTools = listOf(
            McpCatalogTool(
                serverName = "search",
                toolName = "shadow",
                description = "Must never shadow built-in kodex search.",
                inputSchema = ObjectPropertyDefinition(),
            ),
        )
        val resolveMcp = { _: CliBackendTarget? ->
            listOf(
                McpServerDomainCommand("github", "GitHub repository tools.", githubTools),
                McpServerDomainCommand("search", "", conflictingTools),
            )
        }

        // 1. `kodex github --help` lists `create_issue`
        val domainHelp = mutableListOf<String>()
        assertEquals(0, dispatchCli(listOf("github", "--help"), { msg, err ->
            assertFalse(err)
            domainHelp += msg
        }, resolveMcpDomains = resolveMcp) { fail("Help must not execute") })
        assertContains(domainHelp.single(), "GitHub repository tools.")
        assertContains(domainHelp.single(), "create_issue")

        // 2. `kodex github create_issue --help` prints Input JSON Schema in epilog
        val toolHelp = mutableListOf<String>()
        assertEquals(0, dispatchCli(listOf("github", "create_issue", "--help"), { msg, err ->
            assertFalse(err)
            toolHelp += msg
        }, resolveMcpDomains = resolveMcp) { fail("Help must not execute") })
        assertContains(toolHelp.single(), "Input JSON Schema:")
        assertContains(toolHelp.single(), "\"title\"")

        // 3. `kodex github create_issue --json '{"title":"Bug"}'` dispatches McpServerToolCommand
        var invokedTool: McpServerToolCommand? = null
        assertEquals(0, dispatchCli(
            listOf("github", "create_issue", "--json", """{"title":"Bug"}"""),
            { _, _ -> fail("Unexpected output") },
            resolveMcpDomains = resolveMcp,
        ) { leaf ->
            invokedTool = assertIs<McpServerToolCommand>(leaf)
            0
        })
        assertEquals("github", assertNotNull(invokedTool).tool.serverName)
        assertEquals("create_issue", assertNotNull(invokedTool).tool.toolName)

        // 4. Built-in `kodex search -q test` wins over conflicting MCP server name `search`
        var invokedSearch: SearchCommand? = null
        assertEquals(0, dispatchCli(
            listOf("search", "-q", "kotlin"),
            { _, _ -> fail("Unexpected output") },
            resolveMcpDomains = { fail("Built-in search must not even query MCP domains") },
        ) { leaf ->
            invokedSearch = assertIs<SearchCommand>(leaf)
            0
        })
        assertEquals(listOf("kotlin"), assertNotNull(invokedSearch).queries)
    }

    test("framework handles repeated and equals options before canonical deduplication") {
        val arguments = listOf(
            "backend", "start", "--web-origin", "http://127.0.0.1:8080",
            "--web-origin=http://localhost:80", "--web-origin", "http://localhost",
        )
        assertEquals(0, dispatchCli(arguments, { _, _ -> fail("Unexpected output") }) { leaf ->
            val start = assertIs<BackendStartCommand>(leaf)
            assertEquals(setOf("http://127.0.0.1:8080", "http://localhost"), start.browserOrigins.toSet())
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
