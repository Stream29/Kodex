package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult as SdkCallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool as SdkTool

val mcpRouteAdmissionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("an MCP transport execution failure becomes an error CallToolResult") {
        withOfflineOwners(listOf("server" to listOf("echo"))) { owners, catalogs, transports ->
            owners.single().reconnect()
            val tool = catalogs.getValue("server").listTools().single()
            var attempts = 0
            transports.getValue("server").single().beforeToolCall = {
                attempts++
                error("offline fixture failure")
            }
            val result = tool.call(JsonObject(emptyMap()))
            assertEquals(true, result.isError)
            assertFalse(result.content.toString().contains("offline fixture failure"))
            assertEquals(1, attempts)
        }
    }

    test("auth-blocked client returns authentication error without calling transport") {
        withOfflineOwners(listOf("server" to listOf("echo"))) { owners, catalogs, transports ->
            owners.single().reconnect()
            val tool = catalogs.getValue("server").listTools().single()
            val blocked = McpAuthenticationBlockedClient("server", listOf(tool)).listTools().single()
            val result = blocked.call(JsonObject(emptyMap()))
            assertEquals(true, result.isError)
            assertTrue(result.content.toString().contains("requires authentication"))
            assertTrue(transports.getValue("server").single().calledNames.isEmpty())
        }
    }

    test("servers a-b and a_b coexist while newly added reserved CLI domain names are rejected") {
        withOfflineOwners(listOf("a-b" to listOf("echo"), "a_b" to listOf("echo"))) { owners, catalogs, _ ->
            owners.forEach { it.reconnect() }
            val tools = catalogs.values.flatMap { it.listTools() }
            assertEquals(listOf("a-b", "a_b"), tools.map { it.serverName }.sorted())
            val configurations = owners.associate { it.serverName to it.configuration }
            validateMcpConfigurationUpdate(emptyMap(), configurations)

            val reservedUpdate = configurations + ("terminal" to McpServerConfiguration.Stdio("offline"))
            assertFailsWith<IllegalArgumentException> {
                validateMcpConfigurationUpdate(configurations, reservedUpdate)
            }
            // Existing persisted reserved server remains editable (e.g. disable) and deletable.
            validateMcpConfigurationUpdate(
                reservedUpdate,
                reservedUpdate + ("terminal" to McpServerConfiguration.Stdio("offline", enabled = false)),
            )
            validateMcpConfigurationUpdate(reservedUpdate, configurations)
        }
    }

    test("blank server name fails service creation before spawning children") {
        val beforeChildren = currentCoroutineContext().job.children.toList()
        assertFailsWith<IllegalArgumentException> {
            CoroutineScope(currentCoroutineContext()).McpServiceImpl(
                MutableStateFlow(offlineSettings(mapOf(" " to McpServerConfiguration.Stdio("offline")))),
            )
        }
        assertEquals(beforeChildren, currentCoroutineContext().job.children.toList())
    }

    test("duplicate or blank tool names in SDK catalog fail ToolCatalog without publishing") {
        withOfflineOwners(listOf("bad" to listOf("dup", "dup"), "healthy" to listOf("x.y"))) { owners, catalogs, transports ->
            owners.forEach { it.reconnect() }
            val bad = owners.first()
            assertEquals(McpClientState.Failed(McpClientFailureReason.ToolCatalog), bad.state.value)
            assertFalse("bad" in catalogs)
            assertTrue(transports.getValue("bad").single().closed)

            val healthyTool = catalogs.getValue("healthy").listTools().single()
            assertEquals("healthy", healthyTool.serverName)
            assertEquals("x.y", healthyTool.toolName)
            val result = healthyTool.call(JsonObject(emptyMap()))
            assertFalse(result.isError == true)
            assertEquals(listOf("x.y"), transports.getValue("healthy").single().calledNames)
        }
    }

    test("invalid catalog on refresh and reconnect retains previous catalog and reports ToolCatalog") {
        withOfflineOwners(listOf("server" to listOf("echo"))) { owners, catalogs, transports ->
            val owner = owners.single()
            owner.reconnect()
            val previous = catalogs.getValue("server")
            transports.getValue("server").single().toolNames = listOf("dup", "dup")
            owner.refresh()
            assertSame(previous, catalogs.getValue("server"))
            assertEquals(McpClientState.Failed(McpClientFailureReason.ToolCatalog), owner.state.value)
            val unavailable = previous.listTools().single().call(JsonObject(emptyMap()))
            assertTrue(unavailable.isError == true)
            assertTrue(transports.getValue("server").single().calledNames.isEmpty())
            owner.reconnect()
            assertSame(previous, catalogs.getValue("server"))
            assertEquals(McpClientState.Failed(McpClientFailureReason.ToolCatalog), owner.state.value)
            assertTrue(transports.getValue("server").last().closed)
            transports.getValue("server").last().toolNames = listOf("echo")
            owner.reconnect()
            assertEquals(McpClientState.Healthy, owner.state.value)
            assertNotSame(previous, catalogs.getValue("server"))
        }
    }
}

private fun offlineSettings(configurations: Map<String, McpServerConfiguration>): McpSettings =
    object : McpSettings { override val mcpServers = configurations }

internal suspend fun withOfflineOwners(
    servers: List<Pair<String, List<String>>>,
    block: suspend CoroutineScope.(
        List<McpClientOwner>, MutableMap<String, McpClient>, MutableMap<String, MutableList<OfflineSdkTransport>>,
    ) -> Unit,
) = withTimeout(10.seconds) {
    val scope = CoroutineScope(currentCoroutineContext()).supervisorChildScope()
    val catalogs = mutableMapOf<String, McpClient>()
    val transports = mutableMapOf<String, MutableList<OfflineSdkTransport>>()
    val owners = servers.map { (name, tools) ->
        McpClientOwner(
            scope, name, McpServerConfiguration.Stdio("offline-not-a-process"),
            openTransport = {
                OfflineSdkTransport(transports[name]?.lastOrNull()?.toolNames ?: tools)
                    .also { transports.getOrPut(name) { mutableListOf() } += it }
            },
            publishCatalog = { owner, catalog ->
                catalogs[owner.serverName] = owner.client(catalog)
                true
            },
        )
    }
    try {
        scope.block(owners, catalogs, transports)
    } finally {
        owners.forEach { it.close() }
        scope.cancelAndJoin()
    }
}

/** Typed protocol responses consumed by the real SDK Client; no sockets or child processes. */
internal class OfflineSdkTransport(var toolNames: List<String>) : Transport {
    val calledNames = mutableListOf<String>()
    var closed = false
        private set
    var beforeToolsResponse: suspend () -> Unit = {}
    var beforeToolCall: suspend () -> Unit = {}
    private var receive: suspend (JSONRPCMessage) -> Unit = {}
    private var closedCallback: () -> Unit = {}

    override suspend fun start(): Unit = Unit
    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        if (message !is JSONRPCRequest) return
        val response: RequestResult = when (message.method) {
            "initialize" -> InitializeResult(
                protocolVersion = "2025-11-25",
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
                serverInfo = Implementation("offline-sdk-fixture", "1.0"),
            )
            "tools/list" -> {
                val result = ListToolsResult(toolNames.map { SdkTool(it, ObjectPropertyDefinition()) })
                beforeToolsResponse()
                result
            }
            "tools/call" -> {
                beforeToolCall()
                calledNames += requireNotNull(message.params).jsonObject.getValue("name").jsonPrimitive.content
                SdkCallToolResult(content = emptyList(), isError = false)
            }
            else -> error("Unexpected offline MCP request: ${message.method}")
        }
        receive(JSONRPCResponse(message.id, response))
    }
    override suspend fun close() {
        if (closed) return
        closed = true
        closedCallback()
    }
    override fun onClose(block: () -> Unit) { closedCallback = block }
    override fun onError(block: (Throwable) -> Unit): Unit = Unit
    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) { receive = block }
}
