package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

val codexMcpSettingsValuesTest by testSuite {
    val json = Json { encodeDefaults = true }
    val serializer = McpCodexImportCandidate.serializer()

    test("supported settings preserve complete configurations and derive transport") {
        val configurations = listOf(
            McpServerConfiguration.StreamableHttp(
                url = "https://example.invalid/mcp",
                headers = mapOf("X-Test" to McpSecret("dummy-header")),
                oauth = McpOAuthConfiguration.Uninitialized(
                    client = McpOAuthClient(clientId = "test-client"),
                    scopes = listOf("test-scope"),
                ),
            ),
            McpServerConfiguration.Stdio(
                command = "test-command",
                args = listOf("--test"),
                environment = mapOf("TEST_KEY" to McpSecret("dummy-environment")),
                workingDirectory = Path("test-workdir"),
                enabled = false,
            ),
        )
        for ((index, configuration) in configurations.withIndex()) {
            val value = McpCodexImportCandidate.Supported("server-$index", configuration)
            val encoded = json.encodeToJsonElement(serializer, value)
            assertEquals(setOf("type", "serverName", "configuration"), encoded.jsonObject.keys)
            val decoded = assertIs<McpCodexImportCandidate.Supported>(
                json.decodeFromJsonElement(serializer, encoded),
            )
            assertEquals(value, decoded)
            assertEquals(value.transport, decoded.transport)
            val conflicting = JsonObject(
                encoded.jsonObject + ("transport" to JsonPrimitive("contradictory")),
            )
            assertFailsWith<IllegalArgumentException> {
                json.decodeFromJsonElement(serializer, conflicting)
            }
        }
    }

    test("unsupported reasons nullable transport and empty lists round trip") {
        val values = (listOf(null) + McpTransportKind.entries).map { transport ->
            McpCodexImportCandidate.Unsupported(
                serverName = "unsupported-${transport?.name}",
                transport = transport,
                detail = "Unsupported fields: test_field.",
            )
        }
        val listSerializer = ListSerializer(serializer)
        val encoded = json.encodeToString(listSerializer, values)
        assertEquals(values, json.decodeFromString(listSerializer, encoded))
        assertEquals(emptyList(), json.decodeFromString(listSerializer, "[]"))
    }

    test("decoding retains the existing unsupported reason validation") {
        val value = McpCodexImportCandidate.Unsupported("test", null, "Unsupported declaration.")
        val encoded = json.encodeToJsonElement(serializer, value).jsonObject
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromJsonElement(
                serializer,
                JsonObject(encoded + ("detail" to JsonPrimitive(" "))),
            )
        }
    }
}
