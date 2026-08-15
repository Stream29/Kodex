package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

val mcpServiceImplIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("settings reload only replaces changed clients and refresh only reloads tools") {
        val fixture = McpServiceHttpFixture().start()
        val settings = MutableStateFlow(
            TestMcpSettings(
                mapOf(
                    "alpha" to fixture.configuration("alpha", "first"),
                    "beta" to fixture.configuration("beta", "stable"),
                ),
            ),
        )
        val scope = CoroutineScope(currentCoroutineContext())
        val service = scope.McpServiceImpl(settings)
        try {
            val initialClients = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients.size == 2 &&
                        clients.allTools().size == 2
                }
            }
            initialClients.values.forEach { client ->
                withTimeout(10.seconds) {
                    client.state.first { state -> state == McpClientState.Healthy }
                }
            }
            assertEquals(
                setOf("mcp__alpha", "mcp__beta"),
                initialClients.allTools().namespaceNames(),
            )
            assertEquals(1, fixture.initializeCount("alpha"))
            assertEquals(1, fixture.initializeCount("beta"))
            val initialAlpha = initialClients.getValue("alpha")
            val initialBeta = initialClients.getValue("beta")

            settings.value = TestMcpSettings(
                mapOf(
                    "alpha" to fixture.configuration("alpha", "second"),
                    "beta" to fixture.configuration("beta", "stable"),
                ),
            )
            val reconfiguredClients = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients["alpha"]?.let { client ->
                        client !== initialAlpha &&
                            client.listTools().size == 1
                    } == true
                }
            }
            withTimeout(10.seconds) {
                reconfiguredClients.getValue("alpha").state.first { state ->
                    state == McpClientState.Healthy
                }
            }
            assertNotSame(initialAlpha, reconfiguredClients.getValue("alpha"))
            assertSame(initialBeta, reconfiguredClients.getValue("beta"))
            assertEquals(McpClientState.Closed, initialAlpha.state.value)
            assertEquals(1, fixture.initializeCount("beta"))
            assertEquals(1, fixture.toolsListCount("beta"))

            fixture.catalogVersion.set(2)
            service.refresh()
            assertEquals(2, fixture.initializeCount("alpha"))
            assertEquals(1, fixture.initializeCount("beta"))
            assertEquals(3, fixture.toolsListCount("alpha"))
            assertEquals(2, fixture.toolsListCount("beta"))
            val refreshedClients = service.clients.value
            assertNotSame(
                reconfiguredClients.getValue("alpha"),
                refreshedClients.getValue("alpha"),
            )
            assertNotSame(
                reconfiguredClients.getValue("beta"),
                refreshedClients.getValue("beta"),
            )
            assertEquals(4, refreshedClients.allTools().sumOf { tool ->
                assertIs<ResponsesApiNamespace>(tool.spec).tools.size
            })

            val alphaTool = refreshedClients.allTools().single { tool ->
                val namespace = assertIs<ResponsesApiNamespace>(tool.spec)
                namespace.name == "mcp__alpha" &&
                    assertIs<ResponsesApiTool>(namespace.tools.single()).name == "echo"
            }
            assertEquals("alpha", alphaTool.serverName)
            assertEquals("alpha tools", alphaTool.serverInstructions)
            val completed = assertIs<StableMcpToolEvent>(
                alphaTool.handle(
                    PendingMcpToolEvent(
                        callId = "alpha-call",
                        name = "echo",
                        namespace = "mcp__alpha",
                        arguments = buildJsonObject { put("name", "Ada") },
                    ),
                ),
            )
            assertEquals(
                "alpha:second:Ada",
                completed.result.content.single().jsonObject.getValue("text").jsonPrimitive.content,
            )

            settings.value = TestMcpSettings(
                mapOf(
                    "alpha" to fixture.configuration("alpha", "second"),
                    "beta" to fixture.configuration("beta", "stable", enabled = false),
                ),
            )
            val remaining = withTimeout(10.seconds) {
                service.clients.first { clients -> clients.keys == setOf("alpha") }
            }
            assertEquals(setOf("mcp__alpha"), remaining.allTools().namespaceNames())
        } finally {
            service.close()
            service.coroutineContext[Job]?.join()
            fixture.stop()
        }

        assertTrue(service.clients.value.isEmpty())
    }

    test("connection loss retains tools and reconnect refreshes the catalog") {
        val fixture = McpServiceHttpFixture().start()
        val service = CoroutineScope(currentCoroutineContext()).McpServiceImpl(
            MutableStateFlow(
                TestMcpSettings(
                    mapOf("alpha" to fixture.configuration("alpha", "stable")),
                ),
            ),
        )
        try {
            val originalClient = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients["alpha"]?.let { client ->
                        client.listTools().size == 1
                    } == true
                }.getValue("alpha")
            }
            withTimeout(10.seconds) {
                originalClient.state.first { state -> state == McpClientState.Healthy }
            }
            val originalTool = originalClient.listTools().single()

            fixture.available.set(false)
            val unavailable = assertIs<StableMcpToolEvent>(
                originalTool.handle(
                    PendingMcpToolEvent(
                        callId = "unavailable-call",
                        name = "echo",
                        namespace = "mcp__alpha",
                        arguments = buildJsonObject { put("name", "Ada") },
                    ),
                ),
            )

            assertEquals(
                McpClientState.Failed(McpClientFailureReason.ConnectionLost),
                originalClient.state.value,
            )
            assertSame(originalClient, service.clients.value.getValue("alpha"))
            assertSame(originalTool, originalClient.listTools().single())
            assertTrue(unavailable.result.isError == true)
            assertTrue(
                unavailable.result.content.single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive
                    .content
                    .contains("is not available"),
            )

            fixture.catalogVersion.set(2)
            fixture.available.set(true)
            originalClient.reconnect()

            val replacement = service.clients.value.getValue("alpha")
            assertNotSame(originalClient, replacement)
            assertSame(originalClient.state, replacement.state)
            assertEquals(McpClientState.Healthy, replacement.state.value)
            assertEquals(1, originalClient.listTools().size)
            assertEquals(2, replacement.listTools().size)
            assertEquals(2, fixture.initializeCount("alpha"))
            assertEquals(2, fixture.toolsListCount("alpha"))
        } finally {
            service.close()
            service.coroutineContext[Job]?.join()
            fixture.stop()
        }
    }

    test("invalidation drops an identical connection and catalog before rebuilding") {
        val fixture = McpServiceHttpFixture().start()
        val service = CoroutineScope(currentCoroutineContext()).McpServiceImpl(
            MutableStateFlow(
                TestMcpSettings(
                    mapOf("alpha" to fixture.configuration("alpha", "stable")),
                ),
            ),
        )
        try {
            val original = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients["alpha"]?.let { client ->
                        client.state.value == McpClientState.Healthy &&
                            client.listTools().size == 1
                    } == true
                }.getValue("alpha")
            }

            service.invalidate("alpha")

            assertEquals(McpClientState.Closed, original.state.value)
            assertTrue(service.clients.value["alpha"]?.listTools().orEmpty().isEmpty())
            val rebuilt = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients["alpha"]?.let { client ->
                        client !== original &&
                            client.state.value == McpClientState.Healthy &&
                            client.listTools().size == 1
                    } == true
                }.getValue("alpha")
            }
            assertNotSame(original, rebuilt)
            assertEquals(2, fixture.initializeCount("alpha"))
        } finally {
            service.close()
            service.coroutineContext[Job]?.join()
            fixture.stop()
        }
    }

    test("OAuth blocking retains a logical client and the last catalog") {
        val fixture = McpServiceHttpFixture().start()
        val initialOAuth = testInitializedOAuth("first-access-token")
        val initialConfiguration = fixture.configuration("oauth", "stable").copy(
            oauth = initialOAuth,
        )
        val settings = MutableStateFlow(
            TestMcpSettings(mapOf("oauth" to initialConfiguration)),
        )
        val service = CoroutineScope(currentCoroutineContext()).McpServiceImpl(settings)
        try {
            val authorizedClient = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients["oauth"]?.let { client ->
                        client.state.value == McpClientState.Healthy &&
                            client.listTools().size == 1
                    } == true
                }.getValue("oauth")
            }
            val originalTool = authorizedClient.listTools().single()

            settings.value = TestMcpSettings(
                mapOf(
                    "oauth" to initialConfiguration.copy(
                        oauth = initialOAuth.copy(
                            accessToken = McpSecret("second-access-token"),
                        ),
                    ),
                ),
            )
            delay(100.milliseconds)
            assertSame(authorizedClient, service.clients.value.getValue("oauth"))
            assertSame(originalTool, authorizedClient.listTools().single())

            settings.value = TestMcpSettings(
                mapOf(
                    "oauth" to initialConfiguration.copy(
                        oauth = McpOAuthConfiguration.Uninitialized(
                            client = initialOAuth.client,
                            resource = initialOAuth.resource,
                            scopes = initialOAuth.scopes,
                        ),
                    ),
                ),
            )
            val blocked = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients["oauth"]?.state?.value == McpClientState.AuthenticationBlocked
                }.getValue("oauth")
            }

            assertNotSame(authorizedClient, blocked)
            assertEquals(McpClientState.Closed, authorizedClient.state.value)
            assertSame(originalTool, blocked.listTools().single())
        } finally {
            service.close()
            service.coroutineContext[Job]?.join()
            fixture.stop()
        }
    }

    test("a 401 refresh persists its token without replacing the client catalog") {
        val fixture = McpServiceHttpFixture().start()
        fixture.expectedAuthorization.set("Bearer old-access-token")
        val store = TestMcpConfigurationState(
            mapOf(
                "oauth" to fixture.configuration("oauth", "stable").copy(
                    oauth = testInitializedOAuth("old-access-token"),
                ),
            ),
        )
        val refreshCount = AtomicInteger()
        val refresher = McpOAuthTokenRefresher { initialized ->
            refreshCount.incrementAndGet()
            initialized.copy(accessToken = McpSecret("new-access-token"))
        }
        val service = CoroutineScope(currentCoroutineContext()).McpServiceImpl(
            settings = store.settings,
            configurationStore = store,
            tokenRefresher = refresher,
        )
        try {
            val originalClient = withTimeout(10.seconds) {
                service.clients.first { clients ->
                    clients["oauth"]?.let { client ->
                        client.state.value == McpClientState.Healthy &&
                            client.listTools().size == 1
                    } == true
                }.getValue("oauth")
            }
            val originalTool = originalClient.listTools().single()
            fixture.expectedAuthorization.set("Bearer new-access-token")

            val completed = assertIs<StableMcpToolEvent>(
                originalTool.handle(
                    PendingMcpToolEvent(
                        callId = "refresh-call",
                        name = "echo",
                        namespace = "mcp__oauth",
                        arguments = buildJsonObject { put("name", "Ada") },
                    ),
                ),
            )

            assertTrue(completed.result.isError != true)
            assertEquals(1, refreshCount.get())
            val persisted = assertIs<McpOAuthConfiguration.Initialized>(
                assertIs<McpServerConfiguration.StreamableHttp>(
                    store.configurations.value.getValue("oauth"),
                ).oauth,
            )
            assertEquals(McpSecret("new-access-token"), persisted.accessToken)
            assertSame(originalClient, service.clients.value.getValue("oauth"))
            assertSame(originalTool, originalClient.listTools().single())
            assertEquals(1, fixture.initializeCount("oauth"))
            assertEquals(1, fixture.toolsListCount("oauth"))
        } finally {
            service.close()
            service.coroutineContext[Job]?.join()
            fixture.stop()
        }
    }
}

private fun testInitializedOAuth(accessToken: String): McpOAuthConfiguration.Initialized =
    McpOAuthConfiguration.Initialized(
        client = McpOAuthClient(
            clientId = "client-id",
            clientSecret = McpSecret("client-secret"),
            authorizationEndpoint = "https://issuer.example.test/authorize",
            tokenEndpoint = "https://issuer.example.test/token",
        ),
        resource = "https://resource.example.test",
        scopes = listOf("tools.read"),
        resolvedAuthorizationEndpoint = "https://issuer.example.test/authorize",
        resolvedTokenEndpoint = "https://issuer.example.test/token",
        accessToken = McpSecret(accessToken),
        refreshToken = McpSecret("refresh-token"),
    )

private data class TestMcpSettings(
    override val mcpServers: Map<String, McpServerConfiguration>,
) : McpSettings

private class TestMcpConfigurationState(
    initial: Map<String, McpServerConfiguration>,
) : McpConfigurationStore {
    override val configurations = MutableStateFlow(initial)
    val settings = MutableStateFlow<McpSettings>(TestMcpSettings(initial))

    override suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration> {
        val updated = transform(configurations.value)
        configurations.value = updated
        settings.value = TestMcpSettings(updated)
        return updated
    }
}

private class McpServiceHttpFixture {
    val catalogVersion: AtomicInteger = AtomicInteger(1)
    val available: AtomicBoolean = AtomicBoolean(true)
    val expectedAuthorization: AtomicReference<String?> = AtomicReference(null)

    private val initializeCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val toolsListCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, host = Host, port = 0) {
        routing {
            post("/mcp/{server}") {
                if (!available.get()) {
                    call.respond(HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                expectedAuthorization.get()?.let { expected ->
                    if (call.request.headers[HttpHeaders.Authorization] != expected) {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                }
                val serverName = checkNotNull(call.parameters["server"])
                val request = TestJson.parseToJsonElement(call.receiveText()).jsonObject
                val method = request["method"]?.jsonPrimitive?.content
                val id = request["id"] ?: JsonNull
                call.response.header(SessionHeader, "$SessionId-$serverName")
                when (method) {
                    "initialize" -> {
                        initializeCounts.count(serverName).incrementAndGet()
                        call.respondJson(jsonRpcResult(id, initializeResult(serverName)))
                    }

                    "notifications/initialized" -> call.respond(HttpStatusCode.Accepted)
                    "tools/list" -> {
                        toolsListCounts.count(serverName).incrementAndGet()
                        call.respondJson(jsonRpcResult(id, toolsResult(catalogVersion.get())))
                    }

                    "tools/call" -> call.respondToolCall(serverName, request, id)
                    else -> call.respond(HttpStatusCode.NotFound)
                }
            }
            get("/mcp/{server}") {
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
            delete("/mcp/{server}") {
                call.respond(HttpStatusCode.Accepted)
            }
        }
    }

    private var port: Int = 0

    suspend fun start(): McpServiceHttpFixture {
        server.startSuspend(wait = false)
        port = server.engine.resolvedConnectors().single().port
        return this
    }

    fun configuration(
        serverName: String,
        marker: String,
        enabled: Boolean = true,
    ): McpServerConfiguration.StreamableHttp =
        McpServerConfiguration.StreamableHttp(
            url = "http://$Host:$port/mcp/$serverName",
            headers = mapOf(MarkerHeader to McpSecret(marker)),
            enabled = enabled,
        )

    fun initializeCount(serverName: String): Int =
        initializeCounts[serverName]?.get() ?: 0

    fun toolsListCount(serverName: String): Int =
        toolsListCounts[serverName]?.get() ?: 0

    suspend fun stop() {
        server.stopSuspend(100, 2_000)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondToolCall(
    serverName: String,
    request: JsonObject,
    id: JsonElement,
) {
    val params = request.getValue("params").jsonObject
    val name = params["arguments"]
        ?.jsonObject
        ?.get("name")
        ?.jsonPrimitive
        ?.content
        ?: "World"
    val marker = this.request.headers[MarkerHeader].orEmpty()
    respondJson(
        jsonRpcResult(
            id,
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", "$serverName:$marker:$name")
                            },
                        )
                    },
                )
                put("isError", false)
            },
        ),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(body: JsonObject) {
    respondText(body.toString(), ContentType.Application.Json)
}

private fun initializeResult(serverName: String): JsonObject = buildJsonObject {
    put("protocolVersion", "2025-03-26")
    put(
        "capabilities",
        buildJsonObject {
            put("tools", buildJsonObject { put("listChanged", true) })
        },
    )
    put(
        "serverInfo",
        buildJsonObject {
            put("name", serverName)
            put("version", "1.0.0")
        },
    )
    put("instructions", "$serverName tools")
}

private fun toolsResult(version: Int): JsonObject = buildJsonObject {
    put(
        "tools",
        buildJsonArray {
            add(toolDefinition("echo"))
            if (version >= 2) add(toolDefinition("second"))
        },
    )
}

private fun toolDefinition(name: String): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "$name test tool")
    put(
        "inputSchema",
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            put("additionalProperties", false)
        },
    )
}

private fun jsonRpcResult(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

private fun ConcurrentHashMap<String, AtomicInteger>.count(name: String): AtomicInteger =
    computeIfAbsent(name) { AtomicInteger() }

private fun List<io.github.stream29.kodex.tool.contract.Tool>.namespaceNames(): Set<String> =
    mapTo(mutableSetOf()) { tool -> assertIs<ResponsesApiNamespace>(tool.spec).name }

private fun Map<String, McpClient>.allTools(): List<McpTool> =
    values.flatMap(McpClient::listTools)

private const val Host: String = "127.0.0.1"
private const val SessionHeader: String = "mcp-session-id"
private const val SessionId: String = "test-session"
private const val MarkerHeader: String = "X-Marker"
private val TestJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
