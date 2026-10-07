package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentruntime.decorator.tool.toolRuntime
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstate.impl.KodexAgentState as createAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.tool.plan.updatePlanTool
import io.github.stream29.kodex.tool.toolsearch.ToolSearchEngine
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult as SdkCallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool as SdkTool

val mcpRouteAdmissionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("two SDK servers a-b and a_b expose the same real route but are rejected before service acquisition") {
        withOfflineOwners(listOf("a-b" to listOf("echo"), "a_b" to listOf("echo"))) { owners, catalogs, _ ->
            owners.forEach { it.reconnect() }
            val tools = catalogs.values.flatMap { it.listTools() }
            assertEquals(2, tools.size)
            assertEquals(listOf("mcp__a_b", "mcp__a_b"), tools.map { assertIs<ResponsesApiNamespace>(it.spec).name })
            // The actual dispatcher still rejects unadmitted duplicate routes;
            // admission must keep them out before even a healthy local pending call.
            assertFailsWith<IllegalArgumentException> { routeOffline(tools, localPlanCall()) }
            val configurations = owners.associate { it.serverName to it.configuration }
            assertFailsWith<IllegalArgumentException> {
                validateMcpConfigurationUpdate(emptyMap(), configurations)
            }
            val beforeChildren = coroutineContext.job.children.toList()
            assertFailsWith<IllegalArgumentException> {
                McpServiceImpl(MutableStateFlow(offlineSettings(configurations)))
            }
            assertEquals(beforeChildren, coroutineContext.job.children.toList())
            assertIs<StablePlanUpdate>(routeOffline(emptyList(), localPlanCall()))
        }
    }

    test("one SDK catalog x-y and x_y fails ToolCatalog without publishing and local routes remain usable") {
        withOfflineOwners(listOf("bad" to listOf("x-y", "x_y"), "healthy" to listOf("echo"))) { owners, catalogs, transports ->
            owners.forEach { it.reconnect() }
            val bad = owners.first()
            assertEquals(McpClientState.Failed(McpClientFailureReason.ToolCatalog), bad.state.value)
            assertFalse("bad" in catalogs)
            assertTrue(transports.getValue("bad").single().closed)
            val healthyTools = catalogs.getValue("healthy").listTools()
            assertIs<StablePlanUpdate>(routeOffline(healthyTools, localPlanCall()))
            val completed = assertIs<StableMcpToolEvent>(routeOffline(
                healthyTools, ResponseItem.FunctionCall(
                    callId = "echo-call", namespace = "mcp__healthy", name = "echo", arguments = "{}",
                ),
            ))
            assertFalse(completed.result.isError == true)
            assertEquals(listOf("echo"), transports.getValue("healthy").single().calledNames)
        }
    }

    test("normal projection and routing keep raw SDK call names and normal model routes unchanged") {
        withOfflineOwners(listOf("alpha-beta" to listOf("x.y"))) { owners, catalogs, transports ->
            owners.single().reconnect()
            val tools = catalogs.getValue("alpha-beta").listTools()
            val namespace = assertIs<ResponsesApiNamespace>(tools.single().spec)
            assertEquals("mcp__alpha_beta", namespace.name)
            assertEquals("x_y", assertIs<ResponsesApiTool>(namespace.tools.single()).name)
            assertEquals("alpha-beta", tools.single().serverName)
            val completed = assertIs<StableMcpToolEvent>(routeOffline(
                tools, ResponseItem.FunctionCall(
                    callId = "raw-call", namespace = namespace.name, name = "x_y", arguments = "{}",
                ),
            ))
            assertFalse(completed.result.isError == true)
            assertEquals(listOf("x.y"), transports.getValue("alpha-beta").single().calledNames)
        }
    }

    test("ambiguous refresh and reconnect retain the previous catalog but report ToolCatalog") {
        withOfflineOwners(listOf("server" to listOf("echo"))) { owners, catalogs, transports ->
            val owner = owners.single()
            owner.reconnect()
            val previous = catalogs.getValue("server")
            transports.getValue("server").single().toolNames = listOf("x-y", "x_y")
            owner.refresh()
            assertSame(previous, catalogs.getValue("server"))
            assertEquals(McpClientState.Failed(McpClientFailureReason.ToolCatalog), owner.state.value)
            val unavailable = assertIs<StableMcpToolEvent>(previous.listTools().single().handle(
                PendingMcpToolEvent(callId = "blocked-call", name = "echo", namespace = "mcp__server", arguments = JsonObject(emptyMap())),
            ))
            assertTrue(unavailable.result.isError == true)
            assertTrue(transports.getValue("server").single().calledNames.isEmpty())
            owner.reconnect()
            assertSame(previous, catalogs.getValue("server"))
            assertEquals(McpClientState.Failed(McpClientFailureReason.ToolCatalog), owner.state.value)
            assertTrue(transports.getValue("server").last().closed)
            // A new connection with a valid catalog can recover normally.
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

private fun localPlanCall(): ResponseItem.FunctionCall =
    ResponseItem.FunctionCall(name = "update_plan", callId = "local-plan", arguments = """{"plan":[]}""")

/** Runs the production routing/dispatch layer over actual State and SDK-projected tools. */
private suspend fun CoroutineScope.routeOffline(
    tools: List<McpTool>,
    call: ResponseItem.FunctionCall,
): StableCleanEvent.CompletedTool {
    var requested = false
    val client = mockOpenAiClient {
        createResponse {
            if (!requested) {
                requested = true
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(0, call),
                    ResponsesStreamEvent.Completed(Response(id = "call", endTurn = false)),
                )
            } else flowOf(
                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                    role = MessageRole.Assistant, content = listOf(ContentItem.OutputText("Done")),
                )),
                ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)),
            )
        }
    }
    val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
    val state = createAgentState(client, storage, TestAgentContextSettings, TestMcpService(tools))
    try {
        state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
        val layer = object : ResumableAgentLayer, KodexAgentState by state {
            override suspend fun resume() {
                while (this.state.value !is KodexAgentStateValue.ToolPending) {
                    if (requestResponseApi() == RequestFinish.Finish) return
                }
            }
        }
        val runtime = layer.toolRuntime(
            fixedTools = listOf(state.updatePlanTool()),
            dynamicTools = MutableStateFlow(tools),
            toolSearch = MutableStateFlow(ToolSearchEngine(emptyList())),
            logger = KotlinLogging.logger("offline-mcp-routing"),
        )
        runtime.resume()
        return (storage.index.valuesIn(0..state.latestIndex.value).map { it.second } +
            storage.work.valuesIn(0..state.latestIndex.value).map { it.second })
            .filterIsInstance<StableCleanEvent.CompletedTool>()
            .single()
    } finally {
        state.cancelAndJoin()
        client.close()
    }
}
