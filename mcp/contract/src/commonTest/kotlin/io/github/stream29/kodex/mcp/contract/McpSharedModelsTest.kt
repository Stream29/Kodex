package io.github.stream29.kodex.mcp.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse

val mcpSharedModelsTest by testSuite {
    test("shared import candidates retain their type identity and derived transport") {
        val candidate: McpCodexImportCandidate = McpCodexImportCandidate.Supported(
            serverName = "example",
            configuration = McpServerConfiguration.StreamableHttp("https://example.test/mcp"),
        )

        val encoded = Json.encodeToJsonElement(McpCodexImportCandidate.serializer(), candidate)

        assertEquals(
            "io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate.Supported",
            encoded.jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertFalse("transport" in encoded.jsonObject)
        assertEquals(
            candidate,
            Json.decodeFromJsonElement(McpCodexImportCandidate.serializer(), encoded),
        )
    }

    test("shared managed state round trips authentication and connection states") {
        val authentications = listOf(
            McpAuthenticationState.NotConfigured,
            McpAuthenticationState.LoginRequired,
            McpAuthenticationState.ReauthorizationRequired,
            McpAuthenticationState.Authorizing,
            McpAuthenticationState.Authorized,
            McpAuthenticationState.Refreshing,
            McpAuthenticationState.Failed("example failure"),
        )
        for (authentication in authentications) {
            val state = McpManagedServerState(
                serverName = "example",
                transport = McpTransportKind.StreamableHttp,
                enabled = true,
                authentication = authentication,
                connection = McpClientState.Failed(McpClientFailureReason.ConnectionLost),
                toolCount = 1,
                headerNames = listOf("Authorization"),
            )

            assertEquals(
                state,
                Json.decodeFromString<McpManagedServerState>(Json.encodeToString(state)),
            )
        }
    }
}
