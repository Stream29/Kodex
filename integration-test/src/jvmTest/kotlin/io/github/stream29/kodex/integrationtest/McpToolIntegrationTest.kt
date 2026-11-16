package io.github.stream29.kodex.integrationtest

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.mcp.impl.McpServiceImpl
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val mcpStreamableHttpIntegrationTest by testSuite {
    test(
        "McpServiceImpl discovers and invokes a Streamable HTTP MCP tool",
        testConfig = TestConfig.testScope(isEnabled = true, timeout = 60.seconds),
    ) {
        withContext(Dispatchers.Default) {
            val fixture = McpToolServerFixture().start()
            val service = CoroutineScope(currentCoroutineContext()).McpServiceImpl(
                MutableStateFlow<McpSettings>(
                    McpProbeSettings(
                        mcpServers = mapOf(
                            McpServerName to McpServerConfiguration.StreamableHttp(fixture.url),
                        ),
                    ),
                ),
            )
            try {
                val mcpClient = withTimeout(20.seconds) {
                    val client = service.clients.first { clients ->
                        clients.values.any { candidate -> candidate.listTools().isNotEmpty() }
                    }.values.single()
                    client.state.first { clientState -> clientState == McpClientState.Healthy }
                    client
                }

                val tool = mcpClient.listTools().single { it.toolName == McpToolName }
                assertEquals(McpServerName, tool.serverName)

                val result = tool.call(buildJsonObject { put("key", McpProbeKey) })
                assertFalse(result.isError == true)
                assertEquals(1, fixture.callCount.get())
                assertEquals(McpProbeKey, fixture.receivedKey.get())
                assertTrue(
                    result.content.any { content -> content.toString().contains("$McpServerMarker:$McpProbeKey") },
                    "Expected the MCP server result to contain the resolved marker.",
                )
            } finally {
                service.close()
                service.coroutineContext.job.join()
                fixture.stop()
            }
        }
    }
}

private data class McpProbeSettings(
    override val mcpServers: Map<String, McpServerConfiguration>,
) : McpSettings

private class McpToolServerFixture {
    val callCount: AtomicInteger = AtomicInteger()
    val receivedKey: AtomicReference<String> = AtomicReference("")

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, host = Host, port = 0) {
        mcpStreamableHttp(enableDnsRebindingProtection = false) {
            createMcpProbeServer()
        }
    }
    private var port: Int = 0

    val url: String
        get() = "http://$Host:$port/mcp"

    suspend fun start(): McpToolServerFixture {
        server.startSuspend(wait = false)
        port = server.engine.resolvedConnectors().single().port
        return this
    }

    suspend fun stop() {
        server.stopSuspend(100, 2_000)
    }

    private fun createMcpProbeServer(): Server = Server(
        serverInfo = Implementation(
            name = "kodex-mcp-integration-probe",
            version = "1.0.0",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructionsProvider = {
            "Use $McpToolName to resolve the deterministic integration-test marker."
        },
    ).apply {
        addTool(
            name = McpToolName,
            description = "Returns the deterministic integration-test marker for the requested key.",
            inputSchema = ObjectPropertyDefinition(
                properties = mapOf(
                    "key" to StringPropertyDefinition(description = "The key to resolve."),
                ),
                required = listOf("key"),
            ),
        ) { request ->
            val key = request.arguments?.get("key")?.jsonPrimitive?.content
                ?: error("The MCP probe requires key.")
            callCount.incrementAndGet()
            receivedKey.set(key)
            CallToolResult(
                content = listOf(TextContent("$McpServerMarker:$key")),
            )
        }
    }
}

private const val Host: String = "127.0.0.1"
private const val McpServerName: String = "integration_probe"
private const val McpToolName: String = "lookup_marker"
private const val McpProbeKey: String = "kodex-mcp-key"
private const val McpServerMarker: String = "MCP_SERVER_EXECUTED"
