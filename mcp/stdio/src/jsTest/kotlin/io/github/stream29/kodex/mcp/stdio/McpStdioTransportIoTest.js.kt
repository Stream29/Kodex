@file:Suppress("UnsafeCastFromDynamic")

package io.github.stream29.kodex.mcp.stdio

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val nodeExecutable: String = js("process.execPath")

val mcpStdioTransportIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("opens a real Node.js stdio MCP child process") {
        val scope = CoroutineScope(currentCoroutineContext())
        val processClient = scope.ProcessClient()
        val transport = processClient.openMcpStdioTransport(
            McpServerConfiguration.Stdio(
                command = nodeExecutable,
                args = listOf("-e", nodeMcpFixture),
                environment = mapOf(TestEnvironmentName to McpSecret(TestEnvironmentValue)),
                workingDirectory = Path("."),
            ),
        )
        val client = Client(
            clientInfo = Implementation("stdio-node-test", "1.0.0"),
            options = ClientOptions(capabilities = ClientCapabilities()),
        )
        try {
            client.connect(transport)
            assertEquals("environment", client.listTools().tools.single().name)
            val result = client.callTool(name = "environment", arguments = emptyMap())
            assertEquals(
                TestEnvironmentValue,
                assertIs<TextContent>(result.content.single()).text,
            )
        } finally {
            client.close()
            processClient.close()
        }
    }
}

private const val TestEnvironmentName: String = "KODEX_MCP_STDIO_NODE_TEST"
private const val TestEnvironmentValue: String = "node-stdio-environment"

private val nodeMcpFixture: String =
    """
    let pending = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => {
      pending += chunk;
      while (true) {
        const newline = pending.indexOf('\n');
        if (newline < 0) return;
        const line = pending.slice(0, newline);
        pending = pending.slice(newline + 1);
        if (line.length === 0) continue;
        const request = JSON.parse(line);
        if (request.id === undefined) continue;
        let result;
        if (request.method === 'initialize') {
          result = {
            protocolVersion: '2025-03-26',
            capabilities: { tools: {} },
            serverInfo: { name: 'node-stdio-fixture', version: '1.0.0' },
          };
        } else if (request.method === 'tools/list') {
          result = {
            tools: [{
              name: 'environment',
              description: 'Reports fixture environment',
              inputSchema: { type: 'object', properties: {}, additionalProperties: false },
            }],
          };
        } else if (request.method === 'tools/call') {
          const text = process.env.KODEX_MCP_STDIO_NODE_TEST || '';
          result = { content: [{ type: 'text', text }] };
        } else {
          process.stdout.write(JSON.stringify({
            jsonrpc: '2.0',
            id: request.id,
            error: { code: -32601, message: 'Method not found' },
          }) + '\n');
          continue;
        }
        process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\n');
      }
    });
    """.trimIndent()
