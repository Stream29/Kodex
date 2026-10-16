package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpOAuthSummary
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val mcpRuntimeValuesTest by testSuite {
    val json = Json { encodeDefaults = true }
    val http = McpManagedServerState(
        serverName = "test-http",
        transport = McpTransportKind.StreamableHttp,
        enabled = true,
        authentication = McpAuthenticationState.Authorized,
        connection = McpClientState.Healthy,
        toolCount = 2,
        headerNames = listOf("Authorization"),
        oauth = McpOAuthSummary(
            clientId = "test-client",
            hasClientSecret = true,
            redirectUri = "http://localhost/callback",
            authorizationEndpoint = "https://example.invalid/authorize",
            tokenEndpoint = "https://example.invalid/token",
            resource = "https://example.invalid/mcp",
            scopes = listOf("test-scope"),
        ),
        streamableHttpUrl = "https://example.invalid/mcp",
    )
    val stdio = McpManagedServerState(
        serverName = "test-stdio",
        transport = McpTransportKind.Stdio,
        enabled = false,
        authentication = McpAuthenticationState.NotConfigured,
        connection = null,
        toolCount = 0,
        environmentNames = listOf("TEST_TOKEN"),
        stdioCommand = "test-command",
        stdioArguments = listOf("--test"),
        stdioWorkingDirectory = Path("/test/work space"),
    )

    test("all MCP authentication states round trip") {
        val states = listOf(
            McpAuthenticationState.NotConfigured,
            McpAuthenticationState.LoginRequired,
            McpAuthenticationState.ReauthorizationRequired,
            McpAuthenticationState.Authorizing,
            McpAuthenticationState.Authorized,
            McpAuthenticationState.Refreshing,
            McpAuthenticationState.Failed("Authorization failed."),
        )
        val serializer = ListSerializer(McpAuthenticationState.serializer())
        assertEquals(states, json.decodeFromString(serializer, json.encodeToString(serializer, states)))
    }

    test("all MCP connection states and failure reasons round trip") {
        val states = listOf(
            McpClientState.AuthenticationBlocked,
            McpClientState.Connecting,
            McpClientState.Healthy,
            McpClientState.Closed,
        ) + McpClientFailureReason.entries.map { McpClientState.Failed(it) }
        val serializer = ListSerializer(McpClientState.serializer())
        assertEquals(states, json.decodeFromString(serializer, json.encodeToString(serializer, states)))
    }

    test("HTTP manager snapshot exposes summaries rather than embedded credentials") {
        val encoded = json.encodeToJsonElement(McpManagedServerState.serializer(), http).jsonObject
        assertEquals(
            setOf(
                "serverName", "transport", "enabled", "authentication", "connection", "toolCount",
                "headerNames", "environmentNames", "oauth", "streamableHttpUrl", "stdioCommand",
                "stdioArguments", "stdioWorkingDirectory",
            ),
            encoded.keys,
        )
        assertEquals(
            setOf(
                "clientId", "hasClientSecret", "redirectUri", "authorizationEndpoint",
                "tokenEndpoint", "resource", "scopes",
            ),
            encoded.getValue("oauth").jsonObject.keys,
        )
        assertEquals(JsonNull, encoded.getValue("stdioWorkingDirectory"))
        assertEquals(http, json.decodeFromJsonElement(McpManagedServerState.serializer(), encoded))
    }

    test("disabled stdio snapshot retains its string path and null connection") {
        val encoded = json.encodeToJsonElement(McpManagedServerState.serializer(), stdio).jsonObject
        assertEquals(JsonPrimitive(stdio.stdioWorkingDirectory.toString()), encoded.getValue("stdioWorkingDirectory"))
        assertEquals(JsonNull, encoded.getValue("connection"))
        assertEquals(JsonNull, encoded.getValue("oauth"))
        assertEquals(stdio, json.decodeFromJsonElement(McpManagedServerState.serializer(), encoded))
    }

    test("manager list retains order and supports an empty snapshot") {
        val serializer = ListSerializer(McpManagedServerState.serializer())
        for (states in listOf(emptyList(), listOf(stdio, http))) {
            assertEquals(states, json.decodeFromString(serializer, json.encodeToString(serializer, states)))
        }
    }

    test("decoding preserves existing manager value validation") {
        val valid = json.encodeToJsonElement(McpManagedServerState.serializer(), http).jsonObject
        val invalidFields = listOf(
            "serverName" to JsonPrimitive(""),
            "toolCount" to JsonPrimitive(-1),
            "headerNames" to JsonArray(listOf(JsonPrimitive("Z"), JsonPrimitive("A"))),
            "environmentNames" to JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("A"))),
        )
        for ((name, value) in invalidFields) {
            assertFailsWith<IllegalArgumentException> {
                json.decodeFromJsonElement(McpManagedServerState.serializer(), JsonObject(valid + (name to value)))
            }
        }
    }
}
