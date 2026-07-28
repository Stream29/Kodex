package io.github.stream29.codex.lite.integrationtest

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentruntime.tool.toolRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.hook.contract.tool.NoOpToolHooks
import io.github.stream29.codex.lite.mcp.contract.McpServerConfiguration
import io.github.stream29.codex.lite.mcp.contract.McpSettings
import io.github.stream29.codex.lite.mcp.impl.McpServiceImpl
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.toolsearch.MutableToolSearchCatalog
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

val openAiMcpToolRoundTripProbeTest by testSuite {
    test(
        "real Responses API invokes a Streamable HTTP MCP tool",
        testConfig = TestConfig.testScope(isEnabled = true, timeout = 240.seconds),
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
            val client = McpRecordingOpenAiClient(realOpenAiClient())
            try {
                withTimeout(20.seconds) {
                    service.tools.first { tools -> tools.isNotEmpty() }
                }
                val toolSearchCatalog = MutableToolSearchCatalog(emptyList())
                val storage = InMemoryCodexAgentStorage(
                    CodexAgentSettings(
                        model = testCodexModel(),
                        instructions = McpProbeInstructions,
                    ),
                )
                val state = CodexAgentState(
                    client = client,
                    storage = storage,
                    contextSettings = TestAgentContextSettings,
                    mcpService = service,
                )
                val runtime = McpRequestOnlyRuntime(state).toolRuntime(
                    tools = emptyList(),
                    dynamicTools = service.tools,
                    localToolSearchDocuments = emptyList(),
                    toolSearchCatalog = toolSearchCatalog,
                    toolHooks = NoOpToolHooks,
                )

                state.appendUserMessage(
                    listOf(
                        ContentItem.InputText(
                            "Resolve key `$McpProbeKey` with the MCP marker tool. " +
                                "You must call the tool before replying, then reply with exactly " +
                                McpAssistantMarker,
                        ),
                    ),
                )
                runtime.resume().toList()

                val history = storage.history.indexes().toList().map { index -> storage.history[index] }
                val call = history
                    .filterIsInstance<ResponseItem.FunctionCall>()
                    .single { item ->
                        item.namespace == "mcp__$McpServerName" &&
                            item.name == McpToolName
                    }
                val output = history
                    .filterIsInstance<ResponseItem.McpToolCallOutput>()
                    .single { item -> item.callId == call.callId }

                assertTrue(
                    history.any { item -> item is ResponseItem.ClientToolSearchCall },
                    "Expected the model to discover the deferred MCP tool.",
                )
                assertEquals(1, fixture.callCount.get())
                assertEquals(McpProbeKey, fixture.receivedKey.get())
                assertTrue(
                    output.output.content.any { content -> content.toString().contains(McpServerMarker) },
                    "Expected the MCP server result to be persisted.",
                )
                assertTrue(
                    client.requests.any { request ->
                        ResponseItem.FunctionCallOutput(
                            callId = output.callId,
                            output = output.output.toFunctionCallOutputPayload(OpenAiJsonCodec),
                        ) in request.input
                    },
                    "Expected the MCP output to be projected and sent back to the Responses API.",
                )
                val assistantText = history
                    .filterIsInstance<ResponseItem.Message>()
                    .lastOrNull { message -> message.role == MessageRole.Assistant }
                    ?.mcpProbeText()
                    ?: fail("Expected a final assistant response.")
                assertTrue(assistantText.contains(McpAssistantMarker))
                assertIs<CodexAgentStateValue.AssistantMessage>(state.state.value)
            } finally {
                client.close()
                service.close()
                service.coroutineContext.job.join()
                fixture.stop()
            }
        }
    }
}

private class McpRecordingOpenAiClient(
    private val delegate: OpenAiClient,
) : OpenAiClient by delegate {
    val requests: MutableList<ResponsesApiRequest> = mutableListOf()

    override suspend fun createResponse(
        request: ResponsesApiRequest,
        installationId: String?,
        turnMetadata: String,
        windowId: String,
    ): Flow<ResponsesStreamEvent> {
        requests += request
        return delegate.createResponse(request, installationId, turnMetadata, windowId)
    }
}

private class McpRequestOnlyRuntime(
    private val delegate: CodexAgentStateContract,
) : CodexAgentRuntime, CodexAgentStateContract by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        emitAll(requestResponseApi())
    }
}

private data class McpProbeSettings(
    override val mcpServers: Map<String, McpServerConfiguration>,
) : McpSettings

private fun ResponseItem.Message.mcpProbeText(): String =
    content.joinToString(separator = "") { item ->
        when (item) {
            is ContentItem.InputText -> item.text
            is ContentItem.OutputText -> item.text
            is ContentItem.InputImage -> ""
        }
    }

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
            name = "codex-lite-mcp-integration-probe",
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
private const val McpProbeKey: String = "codex-lite-mcp-key"
private const val McpServerMarker: String = "MCP_SERVER_EXECUTED"
private const val McpAssistantMarker: String = "MCP_TOOL_ROUND_TRIP_COMPLETED"
private const val McpProbeInstructions: String =
    "For the integration probe, first use tool search to load " +
        "`mcp__integration_probe.lookup_marker`, then call it exactly once with the requested key."
