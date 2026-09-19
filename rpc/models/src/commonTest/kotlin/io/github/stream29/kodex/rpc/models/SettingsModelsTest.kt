package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val settingsModelsTest by testSuite {
    val json = Json { encodeDefaults = true }

    test("backend settings round trip complete MCP credentials without frontend fields") {
        val settings = BackendSettings(
            authSource = KodexAuthSource.Kodex,
            shell = Shell(ShellType.Bash, Path("/bin/bash")),
            contextSources = AgentContextSourceSettings(
                customSources = listOf(AgentContextCustomSource("/test/context")),
            ),
            newSession = KodexNewSessionSettings(),
            sessionTitle = SessionTitleSettings(),
            mcpServers = mapOf(
                "test-http" to McpServerConfiguration.StreamableHttp(
                    url = "https://example.invalid/mcp",
                    headers = mapOf("Authorization" to McpSecret("test-header-secret")),
                    oauth = McpOAuthConfiguration.Initialized(
                        client = McpOAuthClient(
                            clientId = "test-client",
                            clientSecret = McpSecret("test-client-secret"),
                        ),
                        resolvedAuthorizationEndpoint = "https://example.invalid/authorize",
                        resolvedTokenEndpoint = "https://example.invalid/token",
                        accessToken = McpSecret("test-access-token"),
                        refreshToken = McpSecret("test-refresh-token"),
                    ),
                ),
                "test-stdio" to McpServerConfiguration.Stdio(
                    command = "test-command",
                    environment = mapOf("TEST_TOKEN" to McpSecret("test-environment-secret")),
                    workingDirectory = Path("/test/mcp"),
                ),
            ),
        )
        val encoded = json.encodeToJsonElement(BackendSettings.serializer(), settings)
        assertEquals(
            setOf("authSource", "shell", "contextSources", "newSession", "sessionTitle", "mcpServers"),
            encoded.jsonObject.keys,
        )
        for (secret in listOf(
            "test-header-secret",
            "test-client-secret",
            "test-access-token",
            "test-refresh-token",
            "test-environment-secret",
        )) {
            assertTrue(secret in encoded.toString())
        }
        assertEquals(settings, json.decodeFromJsonElement(BackendSettings.serializer(), encoded))
    }

    test("backend settings require an explicit complete initial snapshot") {
        assertFailsWith<SerializationException> {
            json.decodeFromString(BackendSettings.serializer(), "{}")
        }
    }

    test("CLI settings round trip content choices without widths or backend fields") {
        val settings = CliFrontendSettings(
            newLineKey = NewLineKey.Enter,
            sidebars = CliSidebarSettings(
                left = SidebarContent.None,
                right = SidebarContent.HistoryIndex,
            ),
        )
        val encoded = json.encodeToJsonElement(CliFrontendSettings.serializer(), settings).jsonObject
        assertEquals(setOf("newLineKey", "sidebars", "hooks"), encoded.keys)
        assertEquals(setOf("left", "right"), encoded.getValue("sidebars").jsonObject.keys)
        assertEquals(settings, json.decodeFromJsonElement(CliFrontendSettings.serializer(), encoded))
    }

    test("CLI defaults preserve existing key and sidebar content choices") {
        val settings = json.decodeFromString(CliFrontendSettings.serializer(), "{}")
        assertEquals(NewLineKey.ShiftEnter, settings.newLineKey)
        assertEquals(SidebarContent.HistoryIndex, settings.sidebars.left)
        assertEquals(SidebarContent.TerminalSessions, settings.sidebars.right)
        assertTrue(settings.hooks.isEmpty())
    }
}
