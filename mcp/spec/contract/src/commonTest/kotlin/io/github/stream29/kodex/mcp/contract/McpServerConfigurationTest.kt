package io.github.stream29.kodex.mcp.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val mcpServerConfigurationTest by testSuite {
    test("round trips every transport through its public serializer") {
        val configurations = listOf(
            McpServerConfiguration.StreamableHttp(
                url = "https://example.test/mcp",
                headers = mapOf("Authorization" to McpSecret("Bearer test")),
                enabled = false,
            ),
            McpServerConfiguration.StreamableHttp(
                url = "https://dynamic.example.test/mcp",
                oauth = McpOAuthConfiguration.Uninitialized(
                    client = McpOAuthClient(),
                ),
            ),
            McpServerConfiguration.Stdio(
                command = "example-mcp",
                args = listOf("--stdio"),
                environment = mapOf("MCP_TOKEN" to McpSecret("test")),
                workingDirectory = Path("/workspace"),
            ),
        )

        configurations.forEach { configuration ->
            val encoded = Json.encodeToString<McpServerConfiguration>(configuration)

            assertTrue(encoded.contains("\"type\""))
            assertEquals(
                configuration,
                Json.decodeFromString<McpServerConfiguration>(encoded),
            )
        }
    }

    test("redacts every sensitive value from diagnostics") {
        val configuration = McpServerConfiguration.StreamableHttp(
            url = "https://example.test/mcp",
            headers = mapOf("Authorization" to McpSecret("Bearer private")),
            oauth = McpOAuthConfiguration.Initialized(
                client = McpOAuthClient(
                    clientId = "client",
                    clientSecret = McpSecret("client-private"),
                ),
                resolvedAuthorizationEndpoint = "https://example.test/authorize",
                resolvedTokenEndpoint = "https://example.test/token",
                accessToken = McpSecret("access-private"),
                refreshToken = McpSecret("refresh-private"),
            ),
        )

        val diagnostic = configuration.toString()

        assertTrue("Bearer private" !in diagnostic, diagnostic)
        assertTrue("client-private" !in diagnostic, diagnostic)
        assertTrue("access-private" !in diagnostic, diagnostic)
        assertTrue("refresh-private" !in diagnostic, diagnostic)
        assertTrue("<redacted>" in diagnostic, diagnostic)
    }
}
