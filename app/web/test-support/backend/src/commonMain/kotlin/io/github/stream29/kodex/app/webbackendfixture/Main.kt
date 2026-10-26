package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.rpc.websocket.WebSocketRpcTransport
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.krpc.rpcServerConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.krpc.server.KrpcServer
import kotlinx.serialization.json.*
import kotlin.random.Random

public actual fun main(): Unit = runBlocking {
    val token = requireNotNull(environmentVariable("KODEX_WEB_FIXTURE_TOKEN")) { "A fixture token is required." }
    require(token.length >= 32)
    val root = Path(SystemTemporaryDirectory, "kodex-web-fixture-${Random.nextLong()}")
    try {
        val home = prepareKodexHome(root)
        try {
            withBackendServices(
                root, Path(root, "codex"), Path(root, "agents"),
                defaults = defaultBackendSettings().let {
                    it.copy(
                        sessionTitle = SessionTitleSettings(false),
                        newSession = it.newSession.copy(model = OpenAiModelId("test-model")),
                    )
                },
                createClient = {
                    mockOpenAiClient {
                        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                            OpenAiModelId("test-model"), "Offline fixture",
                            contextWindow = 100_000, maxContextWindow = 100_000,
                        )))) }
                        createResponse { flow {
                            val id = ResponseItemId("fixture-answer")
                            val answer = "test answer — 你好"
                            emit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(
                                id, MessageRole.Assistant, emptyList(),
                            )))
                            for (part in listOf("test ", "answer — ", "你好")) {
                                delay(500)
                                emit(ResponsesStreamEvent.OutputTextDelta(
                                    itemId = id.value, outputIndex = 0, contentIndex = 0, delta = part,
                                ))
                            }
                            emit(ResponsesStreamEvent.OutputTextDone(
                                itemId = id.value, outputIndex = 0, contentIndex = 0, text = answer,
                            ))
                            emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                                id, MessageRole.Assistant, listOf(ContentItem.OutputText(answer)),
                            )))
                            emit(ResponsesStreamEvent.Completed(Response(id = "fixture-response", endTurn = true)))
                        } }
                    }
                },
                createLoginClient = { NoFixtureCredentials },
            ) { backend ->
                val engine = embeddedServer(CIO, host = "127.0.0.1", port = 18796) {
                    install(WebSockets) { maxFrameSize = 4 * 1024 * 1024L }
                    routing {
                        webSocket("/fixture") {
                            if (call.request.headers["Host"] != "127.0.0.1:18796" ||
                                call.request.headers["Origin"] != "http://127.0.0.1:18795"
                            ) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin or host rejected."))
                                return@webSocket
                            }
                            val hello = withTimeoutOrNull(5_000) {
                                (incoming.receive() as? Frame.Text)?.readText()?.let {
                                    runCatching { Json.parseToJsonElement(it) }.getOrNull()
                                }
                            }
                            val expected = buildJsonObject {
                                put("token", token)
                                put("instance", "web-fixture-995")
                                put("protocol", 1)
                            }
                            if (hello != expected) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Handshake rejected."))
                                return@webSocket
                            }
                            send(Frame.Text(buildJsonObject {
                                put("ready", true)
                                put("cwd", root.toString())
                            }.toString()))
                            coroutineScope {
                                val transport = WebSocketRpcTransport(this@webSocket, coroutineContext)
                                val server = object : KrpcServer(
                                    rpcServerConfig { serialization { json() } }, transport,
                                ) {}
                                backend.register(server)
                                try { this@webSocket.closeReason.await() }
                                finally { withContext(NonCancellable) { transport.shutdown() } }
                            }
                        }
                    }
                }
                try {
                    engine.startSuspend()
                    println("WEB_FIXTURE_READY 127.0.0.1:18796; enter a line to stop")
                    withContext(Dispatchers.IO) { readlnOrNull() }
                } finally { engine.stopSuspend() }
            }
        } finally { withContext(NonCancellable) { home.closeAndJoin() } }
    } finally { withContext(NonCancellable) { deleteTestDirectory(root) } }
}

internal object NoFixtureCredentials : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        error("The offline Web fixture does not authorize accounts.")
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> = error("No fixture credentials.")
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("No fixture credentials.")
}
