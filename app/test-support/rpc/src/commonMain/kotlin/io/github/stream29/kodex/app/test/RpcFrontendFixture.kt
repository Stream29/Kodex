@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.test

import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.server.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

public actual class RpcFrontendFixture internal constructor(
    actual override val coroutineContext: kotlin.coroutines.CoroutineContext,
    public actual val root: Path,
    public actual val services: RpcServices,
    public actual val views: RpcSessionViews,
    public actual val global: RpcGlobalSettings,
    public actual val sessions: DefaultPersistedSessionViewModelRegistry,
    public actual val drafts: DefaultNewSessionViewModelFactory,
    public actual val models: StateFlow<List<ModelInfo>>,
    public actual val failures: StateFlow<List<Throwable>>,
    private val finish: CompletableDeferred<Unit>,
) : CoroutineScope {
    internal lateinit var completion: Deferred<Unit>

    public actual suspend fun create(name: String): PersistedSessionViewModel {
        val session = sessions.open(services.global.createSession(root))
        session.rename(name)
        session.settings.first { it.threadName == name }
        return session
    }

    public actual fun draft(): NewSessionViewModel =
        drafts.create(NewSessionViewModelArguments(root))

    public actual suspend fun closeAndJoin(): Unit = withContext(NonCancellable) {
        finish.complete(Unit)
        completion.await()
    }
}

public actual fun testSettings(name: String, cwd: Path): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"), cwd = cwd, threadName = name)

public actual suspend fun startRpcFrontendFixture(
    scope: CoroutineScope,
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit,
    seed: suspend CoroutineScope.(Path) -> Unit,
    frontendDispatcher: CoroutineDispatcher?,
): RpcFrontendFixture {
    val ready = CompletableDeferred<RpcFrontendFixture>()
    val finish = CompletableDeferred<Unit>()
    val job = scope.async(Dispatchers.Default) {
        val root = Path(SystemTemporaryDirectory, "kodex-rpc-ui-test-${Random.nextLong()}")
        val client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
            )))) }
            createResponse { flow { response() } }
        }
        try {
            val home = prepareKodexHome(root)
            try {
                seed(root)
                withBackendServices(
                    root, Path(root, "codex"), Path(root, "agents"),
                    defaults = defaultBackendSettings().let {
                        it.copy(
                            sessionTitle = SessionTitleSettings(false),
                            newSession = it.newSession.copy(model = OpenAiModelId("test-model")),
                        )
                    },
                    createClient = { client }, createLoginClient = { TestLoginClient },
                ) { backend ->
                    withInMemoryRpc(backend::register) { raw ->
                        val services = RpcServices(RestoringRpcClient(raw))
                        // Normal completion of the connection block waits for its children.
                        // Retained drafts must be cancelled before that wait, just as the
                        // actual Application closes its frontend owner before RPC teardown.
                        val frontendJob = SupervisorJob(coroutineContext[Job])
                        val frontendScope = CoroutineScope(
                            coroutineContext + frontendJob +
                                (frontendDispatcher ?: Dispatchers.Default),
                        )
                        try {
                            val global = RpcGlobalSettings.open(services.global, frontendScope, 80)
                            try {
                                val models = global.models
                                val failures = MutableStateFlow<List<Throwable>>(emptyList())
                                val views = RpcSessionViews(frontendScope, services, models,
                                    reportUnhandledError = { error -> failures.update { it + error } })
                                val sessions = DefaultPersistedSessionViewModelRegistry(views, models, frontendScope)
                                val drafts = DefaultNewSessionViewModelFactory(views, sessions, global, frontendScope)
                                try {
                                    ready.complete(RpcFrontendFixture(
                                        frontendScope.coroutineContext, root, services, views, global,
                                        sessions, drafts, models, failures, finish,
                                    ))
                                    finish.await()
                                } finally { withContext(NonCancellable) { sessions.shutdown() } }
                            } finally { withContext(NonCancellable) {
                                global.close(); global.join()
                            } }
                        } finally { withContext(NonCancellable) {
                            frontendJob.cancelAndJoin()
                        } }
                    }
                }
            } finally { withContext(NonCancellable) { home.closeAndJoin() } }
        } catch (error: Throwable) {
            ready.completeExceptionally(error)
            throw error
        } finally { withContext(NonCancellable) { deleteTestDirectory(root) } }
    }
    return try { ready.await().also { it.completion = job } }
    catch (error: Throwable) { job.cancel(); withContext(NonCancellable) { job.join() }; throw error }
}

public actual suspend fun withRpcFrontend(
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit,
    block: suspend RpcFrontendFixture.() -> Unit,
): Unit = withContext(Dispatchers.Default) { withTimeout(45.seconds) {
    val fixture = startRpcFrontendFixture(this, response)
    try { fixture.block() } finally { fixture.closeAndJoin() }
} }

public actual suspend fun FlowCollector<ResponsesStreamEvent>.testAnswer() {
    emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
        id = ResponseItemId("answer"), role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("test answer")),
    )))
    emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
}

public actual suspend fun deleteTestDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { deleteTestDirectory(it) }
    SystemCoroutineFileSystem.delete(path)
}

public actual suspend fun CoroutineScope.seedTestHistory(
    home: Path, name: String, events: Map<Int, StableIndexEvent>,
): Int {
    val repository = FileSystemKodexSessionRepository(home, testKodexAgentDependencies())
    try {
        val index = repository.create()
        repository.open(index).runtime.modify { storage ->
            storage.initialize(testSettings(name, home))
            events.forEach { (at, event) -> storage.index[at] = event }
        }
        return index
    } finally { withContext(NonCancellable) { repository.cancelAndJoin() } }
}

private object TestLoginClient : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String = "https://login.example.invalid"
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> =
        error("This fixture does not exchange credentials.")
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> =
        error("This fixture has no credentials.")
}
