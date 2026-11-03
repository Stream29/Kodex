package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.randomBackendPassword
import io.github.stream29.kodex.backend.host.runBackendHost
import io.github.stream29.kodex.cli.app.withLocalFrontendHost
import io.github.stream29.kodex.cli.app.withLocalFrontendPairingHost
import io.github.stream29.kodex.cli.app.LocalAppEngine
import io.github.stream29.kodex.cli.app.runLocalWebFrontend
import io.github.stream29.kodex.backend.LocalFrontendHostJson
import io.github.stream29.kodex.backend.LocalFrontendPairingOffer
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.buffered
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

public actual fun productMain(): Unit = runBlocking {
    environmentVariable("KODEX_CHROME_WINDOW_FIXTURE_URL")?.let { url ->
        val target = Url(url)
        require(target.protocol.name == "http" && target.host == "127.0.0.1" &&
            target.user == null && target.password == null) { "Window fixture accepts only a private loopback URL." }
        val result = runLocalWebFrontend(url, window = true, stopRequested = { false }, engine = LocalAppEngine.Chrome)
        println("CHROME_WINDOW_FIXTURE_EXIT=$result")
        return@runBlocking
    }
    val temporaryDirectory = environmentVariable("TMPDIR")?.takeIf(String::isNotBlank)?.let(::Path)
        ?: SystemTemporaryDirectory
    require(temporaryDirectory.isAbsolute) { "Set TMPDIR to an absolute temporary directory before starting this fixture." }
    val partDelay = environmentVariable("KODEX_BROWSER_FIXTURE_DELAY_MS")?.toLong() ?: 300L
    require(partDelay in 1..5_000)
    val idleTtl = environmentVariable("KODEX_BROWSER_FIXTURE_TTL_SECONDS")?.toInt() ?: 60
    require(idleTtl in 60..600)
    val home = if (environmentVariable("KODEX_BROWSER_FIXTURE_CLI_HOME") == "1") {
        Path(temporaryDirectory, "home", ".kodex")
    } else {
        Path(temporaryDirectory, "kodex-product-browser-${Random.nextLong()}")
    }
    SystemCoroutineFileSystem.createDirectories(home, mustCreate = true)
    SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
    SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"),
        "backend:\n  daemon_ttl_seconds: $idleTtl\n  new_session:\n    model: test-model\n  session_title:\n    enabled: false\nfrontend: {}\n")
    val historyCheck = environmentVariable("KODEX_CHECK_HISTORY") == "1"
    if (historyCheck) seedHistoryProductFixture(home)
    val capabilities = try {
        if (!historyCheck && environmentVariable("KODEX_CHECK_CAPABILITIES") == "1") {
            prepareCapabilityProductFixture(home)
        } else null
    } catch (failure: Throwable) {
        deleteTestDirectory(home)
        throw failure
    }
    val launchDirectory = capabilities?.launchDirectory ?: home
    fun startBackend() = launch {
        runBackendHost(
            home, Path(home, "codex"), Path(home, "agents"),
            browserOrigins = if (historyCheck) setOf("http://127.0.0.1:18795") else emptySet(),
            createClient = {
                mockOpenAiClient {
                    if (capabilities != null) {
                        createRemoteCompactionV2Response { error("Isolated capability fixture compaction failure") }
                    }
                    listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                        OpenAiModelId("test-model"), "Offline browser verification",
                        contextWindow = 100_000, maxContextWindow = 100_000,
                    )))) }
                    createResponse { flow {
                        val id = ResponseItemId("product-browser-answer-${Random.nextLong()}")
                        val parts = if (historyCheck) historyProductStreamParts() else listOf("test ", "answer — ", "你好")
                        val answer = parts.joinToString("")
                        emit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(id, MessageRole.Assistant, emptyList())))
                        for (part in parts) {
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
            createLoginClient = { capabilities?.loginClient() ?: NoFixtureCredentials },
        )
    }
    var backend = startBackend()
    var frontend: Job? = null
    var pairing: Job? = null
    suspend fun startPairing() {
        val origin = environmentVariable("KODEX_BROWSER_FIXTURE_PAIRING_ORIGIN") ?: return
        check(!historyCheck) { "History mode retains its original admission." }
        pairing?.cancelAndJoin()
        val offerFile = Path(home, "frontend-pairing.private")
        SystemFileSystem.delete(offerFile, mustExist = false)
        pairing = launch {
            val endpoint = checkNotNull(BackendInstance.discover(home))
            withLocalFrontendPairingHost(endpoint, launchDirectory.toString(), origin, ::randomBackendPassword) { offer ->
                SystemCoroutineFileSystem.writePrivateString(
                    offerFile, LocalFrontendHostJson.encodeToString(LocalFrontendPairingOffer.serializer(), offer),
                    mustCreate = true,
                )
                println("PRODUCT_PAIRING_READY $offerFile")
                awaitCancellation()
            }
        }
    }
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
        environmentVariable("KODEX_BROWSER_FIXTURE_WEBSITE")?.let { website ->
            require(Path(website).isAbsolute && SystemFileSystem.exists(Path(website, "kodex-web.js")))
            frontend = launch {
                val endpoint = checkNotNull(BackendInstance.discover(home))
                withLocalFrontendHost(endpoint, launchDirectory.toString(), openResource = { asset ->
                    val path = Path(website, asset)
                    if (SystemFileSystem.exists(path)) SystemFileSystem.source(path).buffered() else null
                }, newSecret = ::randomBackendPassword) { privateUrl ->
                    val launchFile = Path(home, "frontend-launch.private")
                    SystemCoroutineFileSystem.writePrivateString(launchFile, privateUrl, mustCreate = true)
                    println("PRODUCT_FRONTEND_READY $launchFile")
                    awaitCancellation()
                }
            }
        }
        // Pairing is explicit and renewable on stdin; no management record is sent to Wasm.
        while (true) {
            when (withContext(Dispatchers.IO) { readlnOrNull() }) {
                "pair" -> if (historyCheck) break else startPairing()
                "restart" -> {
                    check(frontend == null && pairing == null) { "Restart the fixture without its optional frontend hosts." }
                    stopBackend()
                    backend = startBackend()
                    reportReady()
                }
                else -> break
            }
        }
    } finally {
        withContext(NonCancellable) {
            frontend?.cancelAndJoin()
            pairing?.cancelAndJoin()
            stopBackend()
            deleteTestDirectory(home)
        }
    }
}
