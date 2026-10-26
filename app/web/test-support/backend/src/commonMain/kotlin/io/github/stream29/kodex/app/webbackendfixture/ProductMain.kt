package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

public actual fun productMain(): Unit = runBlocking {
    val temporaryDirectory = environmentVariable("TMPDIR")?.takeIf(String::isNotBlank)?.let(::Path)
        ?: SystemTemporaryDirectory
    require(temporaryDirectory.isAbsolute) { "Set TMPDIR to an absolute temporary directory before starting this fixture." }
    val partDelay = environmentVariable("KODEX_BROWSER_FIXTURE_DELAY_MS")?.toLong() ?: 300L
    require(partDelay in 1..5_000)
    val home = Path(temporaryDirectory, "kodex-product-browser-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(home, mustCreate = true)
    SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
    SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"),
        "backend:\n  daemon_ttl_seconds: 60\n  new_session:\n    model: test-model\n  session_title:\n    enabled: false\nfrontend: {}\n")
    fun startBackend() = launch {
        runBackendHost(
            home, Path(home, "codex"), Path(home, "agents"),
            browserOrigins = setOf("http://127.0.0.1:18795"),
            createClient = {
                mockOpenAiClient {
                    listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                        OpenAiModelId("test-model"), "Offline browser verification",
                        contextWindow = 100_000, maxContextWindow = 100_000,
                    )))) }
                    createResponse { flow {
                        val id = ResponseItemId("product-browser-answer-${Random.nextLong()}")
                        val answer = "test answer — 你好"
                        emit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(id, MessageRole.Assistant, emptyList())))
                        for (part in listOf("test ", "answer — ", "你好")) {
                            delay(partDelay)
                            emit(ResponsesStreamEvent.OutputTextDelta(id.value, 0, 0, part))
                        }
                        emit(ResponsesStreamEvent.OutputTextDone(id.value, 0, 0, answer))
                        emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                            id, MessageRole.Assistant, listOf(ContentItem.OutputText(answer)),
                        )))
                        emit(ResponsesStreamEvent.Completed(Response(id = id.value, endTurn = true)))
                    } }
                }
            },
            createLoginClient = { NoFixtureCredentials },
        )
    }
    var backend = startBackend()
    suspend fun stopBackend() {
        withContext(NonCancellable) {
            BackendInstance.discover(home)?.let { runCatching { backendControlRequest(it, "stop") } }
            withTimeoutOrNull(10_000) { backend.join() } ?: backend.cancelAndJoin()
        }
    }
    suspend fun reportReady() {
        withTimeout(30_000) {
            while (true) {
                val endpoint = BackendInstance.discover(home)
                if (endpoint != null && runCatching {
                    backendControlRequest(endpoint, "status")["phase"]?.jsonPrimitive?.content == "ready"
                }.getOrDefault(false)) break
                delay(50)
            }
        }
        println("PRODUCT_BROWSER_READY $home/.locks/backend/connection.json; enter restart or an empty line to stop")
    }
    try {
        reportReady()
        while (withContext(Dispatchers.IO) { readlnOrNull() } == "restart") {
            stopBackend()
            backend = startBackend()
            reportReady()
        }
    } finally {
        withContext(NonCancellable) {
            stopBackend()
            deleteTestDirectory(home)
        }
    }
}
