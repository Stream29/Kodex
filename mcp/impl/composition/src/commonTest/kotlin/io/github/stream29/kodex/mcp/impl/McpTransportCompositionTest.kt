package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpSettings
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import io.github.stream29.kodex.utils.processclient.ProcessException
import io.github.stream29.kodex.utils.processclient.ProcessSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

val mcpTransportCompositionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("production service stdio route reaches the actual ProcessClient spec") {
        val captured = CompletableDeferred<ProcessCommand>()
        var closes = 0
        val processClient: ProcessClient = object : ProcessClient {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
            override suspend fun start(command: ProcessCommand): ProcessSession {
                captured.complete(command)
                throw ProcessException("sentinel-stdio-start")
            }
            override fun close() {
                closes++
            }
        }
        val httpClient = HttpClient(MockEngine { error("Stdio must not make HTTP requests.") }) {
            install(SSE)
        }
        val owner = Job()
        val service = McpServiceImpl(
            scope = CoroutineScope(owner),
            settings = MutableStateFlow(
                transportSettings(
                    McpServerConfiguration.Stdio(
                        command = "sentinel-stdio",
                        args = listOf("literal-arg"),
                        workingDirectory = Path("/sentinel/cwd"),
                        environment = mapOf("SENTINEL" to McpSecret("secret-value")),
                    ),
                ),
            ),
            configurationStore = null,
            tokenRefresher = null,
            processClient = processClient,
            httpClient = httpClient,
        )
        try {
            assertEquals(
                ProcessCommand(
                    "sentinel-stdio", listOf("literal-arg"),
                    Path("/sentinel/cwd"), mapOf("SENTINEL" to "secret-value"),
                ),
                withTimeout(5.seconds) { captured.await() },
            )
            val client = service.clients.value.getValue("sentinel")
            assertIs<McpClientState.Failed>(
                withTimeout(5.seconds) { client.state.first { it is McpClientState.Failed } },
            )
        } finally {
            service.cancel()
            owner.cancelAndJoin()
            httpClient.close()
        }
        assertEquals(1, closes)
    }

    test("production service HTTP route uses configured transport URL and headers not stdio") {
        val captured = CompletableDeferred<Pair<String, String?>>()
        val httpClient = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                captured.complete(request.url.toString() to request.headers["X-Sentinel"])
                error("sentinel-http-request")
            },
        ) {
            install(SSE)
        }
        val processClient: ProcessClient = object : ProcessClient {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
            override suspend fun start(command: ProcessCommand): ProcessSession =
                error("HTTP must not start a stdio process.")
            override fun close(): Unit = Unit
        }
        val owner = Job()
        val service = McpServiceImpl(
            scope = CoroutineScope(owner),
            settings = MutableStateFlow(
                transportSettings(
                    McpServerConfiguration.StreamableHttp(
                        url = "https://sentinel.example.test/mcp",
                        headers = mapOf("X-Sentinel" to McpSecret("configured-value")),
                    ),
                ),
            ),
            configurationStore = null,
            tokenRefresher = null,
            processClient = processClient,
            httpClient = httpClient,
        )
        try {
            assertEquals(
                "https://sentinel.example.test/mcp" to "configured-value",
                withTimeout(5.seconds) { captured.await() },
            )
            val client = service.clients.value.getValue("sentinel")
            assertIs<McpClientState.Failed>(
                withTimeout(5.seconds) { client.state.first { it is McpClientState.Failed } },
            )
        } finally {
            service.cancel()
            owner.cancelAndJoin()
            httpClient.close()
        }
    }
}

private fun transportSettings(configuration: McpServerConfiguration): McpSettings = object : McpSettings {
    override val mcpServers: Map<String, McpServerConfiguration> = mapOf("sentinel" to configuration)
}
