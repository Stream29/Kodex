package io.github.stream29.kodex.cli.session

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.app.test.testSettings
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.cli.rpc.SessionViewStatus
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val sessionBindingOwnershipTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("Session borrows the exact Agent flow and retains last-known name and settings through gated recovery") {
        val fail = CompletableDeferred<Unit>()
        val recovering = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var subscriptions = 0
        var keepalives = 0
        sessionFrontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive" && ++keepalives == 2) {
                        recovering.complete(Unit)
                        resume.await()
                    }
                    return delegate.call(call)
                }
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
                    val upstream = delegate.callServerStreaming<T>(call)
                    if (call.callableName != "getStateFlow" || ++subscriptions != 1) return upstream
                    return flow {
                        coroutineScope {
                            val breaker = launch { fail.await(); throw SessionNotActive() }
                            try { emitAll(upstream) } finally { breaker.cancel() }
                        }
                    }
                }
            }
        }) {
            try {
                val session = create("last-known")
                val view = (session as RpcPersistedSessionViewModel).view
                val firstAgent = assertNotNull(session.rootAgent.value)
                val settings = session.settings.value
                assertSame(view.agent, session.rootAgent)
                assertSame(view.current().settings, firstAgent.settings)
                fail.complete(Unit)
                recovering.await()
                session.lifecycle.first { it == PersistedSessionLifecycleState.Loading }
                assertNull(session.rootAgent.value)
                assertNull(view.binding.value)
                assertEquals(settings, session.settings.value)
                assertEquals("last-known", session.name.value)
                firstAgent.lifecycle.first { it == AgentLifecycleState.Closed }
                assertFailsWith<IllegalStateException> { session.rename("stale") }
                resume.complete(Unit)
                val nextAgent = assertNotNull(session.rootAgent.filterNotNull().first())
                view.status.first { it == SessionViewStatus.Ready }
                session.lifecycle.first { it == PersistedSessionLifecycleState.Open }
                assertNotSame(firstAgent, nextAgent)
                assertSame(view.current().settings, nextAgent.settings)
                assertSame(view.agent, session.rootAgent)
                assertFailsWith<IllegalArgumentException> {
                    session.fork(firstAgent, 1, view.current().storage.index.cacheNonce.value)
                }
                session.rename("current")
                session.name.first { it == "current" }
                assertNotNull(session.readCreatedAt())
                assertNotNull(session.readUpdatedAt())
            } finally { resume.complete(Unit) }
        }
    }

    test("one cancelled registry opener does not evict the shared initializing raw view") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var keepalives = 0
        sessionFrontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive") {
                        keepalives++
                        entered.complete(Unit)
                        proceed.await()
                    }
                    return delegate.call(call)
                }
            }
        }) {
            try {
                val index = services.global.createSession(testSettings("", home))
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { sessions.open(index) }
                entered.await()
                val next = async(start = CoroutineStart.UNDISPATCHED) { sessions.open(index) }
                assertFalse(next.isCompleted)
                cancelled.cancelAndJoin()
                assertFailsWith<CancellationException> { cancelled.await() }
                proceed.complete(Unit)
                val session = next.await()
                assertEquals(1, keepalives)
                assertSame(session, sessions.open(index))
                assertSame((session as RpcPersistedSessionViewModel).view.agent, session.rootAgent)
            } finally { proceed.complete(Unit) }
        }
    }

    test("parent owner cancellation withdraws borrowed Agent before local owners complete") {
        sessionFrontend {
            val session = create("cached-on-close") as RpcPersistedSessionViewModel
            val agent = assertNotNull(session.rootAgent.value)
            val settings = session.settings.value
            owner.cancelAndJoin()
            assertNull(session.rootAgent.value)
            assertNull(session.view.binding.value)
            assertEquals(SessionViewStatus.Closed, session.view.status.value)
            assertEquals(PersistedSessionLifecycleState.Closed, session.lifecycle.value)
            assertEquals(AgentLifecycleState.Closed, agent.lifecycle.value)
            assertEquals(settings, session.settings.value)
            assertEquals("cached-on-close", session.name.value)
            assertTrue(services.global.getSessionCatalog(true).single().isActive)
            assertFailsWith<CancellationException> { session.rename("after-close") }
        }
    }

    test("shutdown cancellation still waits for gated observer cleanup and never stops backend owner") {
        val cleaning = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var subscribed = false
        sessionFrontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
                    val upstream = delegate.callServerStreaming<T>(call)
                    if (call.callableName != "getStateFlow" || subscribed) return upstream
                    subscribed = true
                    return flow {
                        try { emitAll(upstream) }
                        finally {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                finish.await()
                            }
                        }
                    }
                }
            }
        }) {
            try {
                val session = create() as RpcPersistedSessionViewModel
                val shutdown = async(start = CoroutineStart.UNDISPATCHED) { sessions.shutdown() }
                cleaning.await()
                assertNull(session.rootAgent.value)
                shutdown.cancel()
                assertFalse(shutdown.isCompleted)
                finish.complete(Unit)
                shutdown.join()
                assertEquals(PersistedSessionLifecycleState.Closed, session.lifecycle.value)
                assertTrue(services.global.getSessionCatalog(true).single().isActive)
                assertFailsWith<IllegalStateException> { sessions.open(session.sessionIndex) }
            } finally { finish.complete(Unit) }
        }
    }

    /**
     * Central runner must execute this against the unchanged baseline release first.
     * The Closed observer queues registry.open while release holds its real mutex;
     * independent exact-view cleanup makes a replacement possible before index release.
     */
    test("gated registry release cannot close a fresh view registered by an opener waiting on its mutex") {
        var inlineUnarchive = false
        sessionFrontend(decorate = { delegate ->
            object : RpcClient by delegate {
                @Suppress("UNCHECKED_CAST")
                override suspend fun <T> call(call: RpcCall): T {
                    // Already unarchived by the initial real call. Return that idempotent
                    // Unit locally only for this scheduling experiment, so the mutex
                    // handoff reaches raw open without an unrelated transport suspension.
                    if (inlineUnarchive && call.callableName == "unarchiveSession") return Unit as T
                    return delegate.call(call)
                }
            }
        }) {
            val first = create("release-race") as RpcPersistedSessionViewModel
            val tasks = CompletableDeferred<Pair<Deferred<Unit>, Deferred<Result<PersistedSessionViewModel>>>>()
            inlineUnarchive = true
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                first.view.status.first { it == SessionViewStatus.Closed }
                val rawRelease = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    views.release(first.view)
                }
                val opening = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { sessions.open(first.sessionIndex) }
                }
                tasks.complete(rawRelease to opening)
            }
            try {
                sessions.release(first.sessionIndex)
                val (rawRelease, opening) = tasks.await()
                rawRelease.await()
                val reopened = opening.await().getOrThrow()
                assertNotSame(first, reopened)
                assertEquals(SessionViewStatus.Ready, (reopened as RpcPersistedSessionViewModel).view.status.value)
                assertNotNull(reopened.rootAgent.value)
                assertSame(reopened, sessions.open(first.sessionIndex))
                assertTrue(services.global.getSessionCatalog(true).single().isActive)
            } finally {
                inlineUnarchive = false
                observer.cancelAndJoin()
            }
        }
    }
}

private class SessionFrontendFixture(
    override val coroutineContext: CoroutineContext,
    val owner: Job,
    val services: RpcServices,
    val views: RpcSessionViews,
    val sessions: DefaultPersistedSessionViewModelRegistry,
    val home: Path,
) : CoroutineScope {
    suspend fun create(name: String = "Test Session"): PersistedSessionViewModel {
        val session = sessions.open(services.global.createSession(testSettings("", home)))
        session.rename(name)
        session.name.first { it == name }
        session.settings.first { it.threadName == name }
        return session
    }
}

/** Owns a real backend/JSON connection; only external APIs and specified transport gates are mocked. */
private suspend fun sessionFrontend(
    decorate: (RpcClient) -> RpcClient = { it },
    block: suspend SessionFrontendFixture.() -> Unit,
) = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(40.seconds) {
    val home = Path(SystemTemporaryDirectory, "kodex-session-ownership-${Random.nextLong()}")
    val client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { flow { testAnswer() } }
    }
    try {
        withBackendServices(
            home, Path(home, "codex"), Path(home, "agents"),
            defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
            createClient = { client }, createLoginClient = { OwnershipLoginClient },
        ) { backend ->
            withInMemoryRpc(backend::register) { raw ->
                val services = RpcServices(decorate(RestoringRpcClient(raw)))
                val models = MutableStateFlow(services.global.getModels())
                val owner = SupervisorJob(coroutineContext[Job])
                val local = CoroutineScope(coroutineContext + owner)
                val views = RpcSessionViews(local, services, models)
                val sessions = DefaultPersistedSessionViewModelRegistry(views, models, local)
                try {
                    SessionFrontendFixture(coroutineContext, owner, services, views, sessions, home).block()
                } finally {
                    withContext(NonCancellable) {
                        sessions.shutdown()
                        owner.cancelAndJoin()
                    }
                }
            }
        }
    } finally { withContext(NonCancellable) { deleteTestDirectory(home) } }
} }

private object OwnershipLoginClient : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String = "https://login.example.invalid"
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> = error("No credentials are exchanged by these tests.")
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("These tests have no credentials.")
}
