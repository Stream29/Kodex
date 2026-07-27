package io.github.stream29.codex.lite.mcp.contract

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
                headers = mapOf("Authorization" to "Bearer test"),
                enabled = false,
            ),
            McpServerConfiguration.Stdio(
                command = "example-mcp",
                args = listOf("--stdio"),
                environment = mapOf("MCP_TOKEN" to "test"),
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
}
