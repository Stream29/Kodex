package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.openCliFrontendSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

private val rootBoundaryDispatcher = Dispatchers.Default.limitedParallelism(1)

/** Full production root + original Application owner + real JSON/backend, with gated command faults. */
val applicationRootBoundaryTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (operation in listOf("revert", "fork", "open-after-fork", "cancel-revert")) {
        test("real root History $operation reports once or propagates cancellation without replay") {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            var reverts = 0
            var forks = 0
            var forked = false
            withBoundaryRoot(decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "revertHistory") reverts++
                        if (call.callableName == "forkSessionHistory") forks++
                        val fail = when (operation) {
                            "revert", "cancel-revert" -> call.callableName == "revertHistory"
                            "fork" -> call.callableName == "forkSessionHistory"
                            else -> forked && call.callableName == "keepSessionAlive"
                        }
                        if (fail) {
                            entered.complete(Unit)
                            proceed.await()
                            finished.complete(Unit)
                            if (operation == "cancel-revert") throw CancellationException("controlled command cancellation")
                            error("controlled $operation")
                        }
                        val result = original.call<T>(call)
                        if (call.callableName == "forkSessionHistory") forked = true
                        return result
                    }
                }
            }) {
                val selected = application.openSession(historyIndex)
                val agent = requireNotNull(selected.rootAgent.value)
                agent.composer.update("unsent exact draft", 6)
                val revision = agent.composer.state.value.revision
                val other = application.navigation.value.tabs.first()
                val failures = mutableListOf<Throwable>()
                try {
                    runBoundaryMosaic {
                        setContentAndSnapshot {
                            SessionTreeCliScreen(application, newLineKey, sidebar, onOperationFailure = { failures += it })
                        }
                        val history = boundarySnapshot("root-history-entry")
                        clickBoundaryText(history, "root-history-entry", secondary = true)
                        val menu = boundarySnapshot("Fork from here")
                        clickBoundaryText(menu, if ("revert" in operation) "Revert and edit" else "Fork from here")
                        withTimeout(5.seconds) { entered.await() }
                        // Genuine root tab input while the captured operation is waiting.
                        clickBoundaryText(boundarySnapshot("[New Session]"), "[New Session]")
                        // Check the final exact selection after releasing the
                        // captured command; do not impose a new root concurrency policy.
                        proceed.complete(Unit)
                        withTimeout(5.seconds) { finished.await() }
                        awaitBoundaryCondition { application.navigation.value.selected === other }
                        yield()
                        if (operation != "cancel-revert") awaitBoundaryCondition { failures.size == 1 }
                        else awaitBoundaryCondition { reverts == 1 && !draw().render(AnsiLevel.NONE, false).contains("Revert and edit") }
                        assertSame(other, application.navigation.value.selected)
                        assertEquals("unsent exact draft", agent.composer.state.value.text)
                        assertEquals(revision, agent.composer.state.value.revision)
                        assertSame(agent, selected.rootAgent.value)
                        assertEquals(if (operation == "cancel-revert") 0 else 1, failures.size)
                        assertEquals(if ("revert" in operation) 1 else 0, reverts)
                        assertEquals(if ("revert" in operation) 0 else 1, forks)
                        assertNull(agent.notification.value, "Do not duplicate the throwing-operation host report.")
                        assertEquals(if (operation == "open-after-fork") 2 else 1,
                            services.global.getSessionCatalog(true).size)
                    }
                } finally { proceed.complete(Unit) }
            }
        }
    }
    test("real root rejected History request uses actual Agent validation and one captured host report") {
        withBoundaryRoot(rejectHistoryRequest = true) {
            val selected = application.openSession(historyIndex)
            val agent = requireNotNull(selected.rootAgent.value)
            agent.composer.update("keep")
            val failures = mutableListOf<Throwable>()
            runBoundaryMosaic {
                setContentAndSnapshot {
                    SessionTreeCliScreen(application, newLineKey, sidebar, onOperationFailure = { failures += it })
                }
                clickBoundaryText(boundarySnapshot("root-history-entry"), "root-history-entry", secondary = true)
                clickBoundaryText(boundarySnapshot("Revert to here"), "Revert to here")
                awaitBoundaryCondition { failures.size == 1 }
                assertIs<CacheNonceMismatch>(failures.single())
                assertEquals("keep", agent.composer.state.value.text)
                assertSame(selected, application.navigation.value.selected)
                assertEquals(1, services.global.getSessionCatalog(true).size)
            }
        }
    }
    for (fork in listOf(false, true)) {
        test("real root catalog ${if (fork) "fork" else "open"} reports actual RPC failure once and retains popup") {
            var calls = 0
            withBoundaryRoot(decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == if (fork) "forkSession" else "keepSessionAlive") {
                            calls++
                            error("controlled catalog failure")
                        }
                        return original.call(call)
                    }
                }
            }) {
                val selected = application.navigation.value.selected
                val failures = mutableListOf<Throwable>()
                runBoundaryMosaic {
                    setContentAndSnapshot {
                        SessionTreeCliScreen(application, newLineKey, sidebar, onOperationFailure = { failures += it })
                    }
                    clickBoundaryText(boundarySnapshot("[Sessions]"), "[Sessions]")
                    val rows = boundarySnapshot("[Root history")
                    val opening = assertIs<ApplicationPopupState.SessionCatalog>(application.popup.value)
                    if (fork) {
                        clickBoundaryText(rows, "[Root history", secondary = true)
                        clickBoundaryText(boundarySnapshot("Fork"), "Fork")
                    } else clickBoundaryText(rows, "[Root history")
                    awaitBoundaryCondition { failures.size == 1 }
                    val remaining = boundarySnapshot("[Root history")
                    assertFalse("No persisted sessions" in remaining)
                    assertSame(opening, application.popup.value)
                    assertSame(selected, application.navigation.value.selected)
                    assertEquals(1, calls)
                    assertEquals(1, services.global.getSessionCatalog(true).size)
                }
            }
        }
    }
    test("equal Session twins mount the actual root, route genuine selection and delete without losing exact survivor") {
        withBoundaryRoot(equalSessions = true) {
            val first = application.openSession(historyIndex)
            val middleIndex = services.global.createSession(testSettings(cwd = root))
            val middle = application.openSession(middleIndex)
            middle.rename("Middle twin")
            middle.name.first { it == "Middle twin" }
            val lastIndex = services.global.createSession(testSettings(cwd = root))
            val selected = application.openSession(lastIndex)
            selected.rename("Selected twin")
            selected.name.first { it == "Selected twin" }
            assertEquals(first, middle)
            assertEquals(middle, selected)
            assertNotSame(first, selected)
            runBoundaryMosaic {
                setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                clickBoundaryText(boundarySnapshot("[Root history]"), "[Root history]")
                awaitBoundaryCondition { application.navigation.value.selected === first }
                clickBoundaryText(boundarySnapshot("[Selected twin]"), "[Selected twin]")
                awaitBoundaryCondition { application.navigation.value.selected === selected }
                clickBoundaryText(boundarySnapshot("[Middle twin]"), "[Middle twin]", secondary = true)
                clickBoundaryText(boundarySnapshot("Delete"), "Delete")
                clickBoundaryText(boundarySnapshot("Delete Middle twin?"), "[Delete]")
                awaitBoundaryCondition { application.navigation.value.tabs.none { it === middle } }
                assertSame(selected, application.navigation.value.selected,
                    "Equality-based indexOf incorrectly selects the first surviving twin.")
                assertTrue(application.navigation.value.tabs.any { it === first })
                assertFalse(application.selectTab(middle), "Departed exact handle is not its surviving twin.")
                clickBoundaryText(boundarySnapshot("[Root history]"), "[Root history]")
                awaitBoundaryCondition { application.navigation.value.selected === first }
                assertFalse("Middle twin" in boundarySnapshot("[Selected twin]"))
            }
        }
    }
}

private class BoundarySession(
    private val original: PersistedSessionViewModel,
    private val equalSessions: Boolean,
    rejectHistoryRequest: Boolean,
) : PersistedSessionViewModel by original {
    override val rootAgent: StateFlow<AgentViewModel?> = if (!rejectHistoryRequest) original.rootAgent else
        MutableStateFlow(requireNotNull(original.rootAgent.value).let { actual ->
            object : AgentViewModel by actual {
                override fun requestHistoryRevert(untilExclusive: Int, expectedGeneration: Long): Long =
                    actual.requestHistoryRevert(untilExclusive, expectedGeneration - 1)
            }
        })
    override fun equals(other: Any?): Boolean =
        this === other || (equalSessions && other is BoundarySession && other.equalSessions)
    override fun hashCode(): Int = if (equalSessions) 0 else System.identityHashCode(this)
}

private class BoundaryRoot(
    val root: Path,
    val services: RpcServices,
    val application: ApplicationViewModel,
    val global: RpcGlobalSettings,
    scope: CoroutineScope,
    val historyIndex: Int,
) {
    val newLineKey = MutableStateFlow(NewLineKey.ShiftEnter)
    val sidebar = createSidebarSettingsViewModel(global, scope, initialized = true)
}

private suspend fun withBoundaryRoot(
    decorate: (RpcClient) -> RpcClient = { it },
    equalSessions: Boolean = false,
    rejectHistoryRequest: Boolean = false,
    block: suspend BoundaryRoot.() -> Unit,
) = withContext(rootBoundaryDispatcher) { withTimeout(45.seconds) {
    val root = Path(SystemTemporaryDirectory, "kodex-root-boundary-${Random.nextLong()}")
    val client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { flow { testAnswer() } }
    }
    try {
        val historyIndex = seedTestHistory(root, "Root history", mapOf(
            1 to StableUserMessage(listOf(ContentItem.InputText("root-history-entry"))),
        ))
        withBackendServices(
            root, Path(root, "codex"), Path(root, "agents"),
            defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
            createClient = { client }, createLoginClient = { BoundaryLoginClient },
        ) { backend ->
            withInMemoryRpc(backend::register) { raw ->
                val owner = SupervisorJob(coroutineContext[Job])
                val local = CoroutineScope(coroutineContext + owner)
                val services = RpcServices(decorate(RestoringRpcClient(raw)))
                val models = MutableStateFlow(services.global.getModels())
                val views = RpcSessionViews(local, services, models)
                val actualSessions = DefaultPersistedSessionViewModelRegistry(views, models, local)
                val handles = mutableMapOf<Int, BoundarySession>()
                val sessions = object : PersistedSessionViewModelRegistry by actualSessions {
                    override suspend fun open(sessionIndex: Int): PersistedSessionViewModel {
                        val actual = actualSessions.open(sessionIndex)
                        return handles.getOrPut(sessionIndex) {
                            BoundarySession(actual, equalSessions, rejectHistoryRequest)
                        }
                    }
                    override suspend fun release(sessionIndex: Int) {
                        actualSessions.release(sessionIndex)
                        handles.remove(sessionIndex)
                    }
                    override suspend fun delete(sessionIndex: Int): Boolean =
                        actualSessions.delete(sessionIndex).also { if (it) handles.remove(sessionIndex) }
                }
                val global = RpcGlobalSettings.open(services.global, openCliFrontendSettings(root), local, 120)
                val app = createApplicationViewModel(
                    sessions, DefaultNewSessionViewModelFactory(views, sessions, models, local),
                    SessionCatalogViewModelFactory { dependencies, interactions ->
                        DefaultSessionCatalogViewModel(local, dependencies, interactions)
                    },
                    RpcSessionCatalogDependencies(services.global),
                    SettingsViewModelFactory { error("No Settings in these root gates.") },
                    OpenAiLoginViewModelFactory { error("No Login in these root gates.") },
                    { error("No directory picker in these root gates.") },
                    { NewSessionViewModelArguments("New Session", testSettings(cwd = root)) },
                    local,
                )
                try { BoundaryRoot(root, services, app, global, local, historyIndex).block() }
                finally { withContext(NonCancellable) {
                    app.close()
                    app.shutdown()
                    views.close()
                    global.close()
                    owner.cancelAndJoin()
                    global.join()
                } }
            }
        }
    } finally { withContext(NonCancellable) { deleteTestDirectory(root) } }
} }

private suspend fun runBoundaryMosaic(block: suspend TestMosaic<String>.() -> Unit) = runMosaicTest {
    state.size.value = Terminal.Size(120, 40)
    try { block() } finally { cancel() }
}

private suspend fun TestMosaic<String>.boundarySnapshot(expected: String): String {
    var snapshot = draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    return try { withTimeout(5.seconds) {
        while (expected !in snapshot) {
            currentCoroutineContext().ensureActive()
            snapshot = try { awaitSnapshot() } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
            }
        }
        snapshot
    } } catch (failure: TimeoutCancellationException) {
        throw AssertionError("Missing root label '$expected' in actual frame:\n$snapshot", failure)
    }
}

private suspend fun TestMosaic<String>.awaitBoundaryCondition(condition: () -> Boolean) = withTimeout(5.seconds) {
    while (!condition()) {
        currentCoroutineContext().ensureActive()
        // A captured failure list is deliberately not Compose state and need
        // not redraw. Do not wait forever for a frame after the condition changed.
        withTimeoutOrNull(50.milliseconds) { awaitSnapshot() }
        yield()
    }
}

private suspend fun TestMosaic<String>.clickBoundaryText(snapshot: String, label: String, secondary: Boolean = false) {
    val lines = snapshot.lines()
    val row = lines.indexOfFirst { label in it }
    assertTrue(row >= 0, snapshot)
    val column = lines[row].indexOf(label) + 1
    val button = if (secondary) MouseEvent.Button.Right else MouseEvent.Button.Left
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, button))
    // Match the real input/frame boundary used by the existing recordings:
    // a Press can mount a popup whose layout must settle before Release.
    repeat(4) { withTimeoutOrNull(50.milliseconds) { awaitSnapshot() } }
    sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release, button))
    repeat(4) { withTimeoutOrNull(50.milliseconds) { awaitSnapshot() } }
}

private object BoundaryLoginClient : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization) = error("No login in these root gates.")
    override suspend fun exchangeAuthorizationCode(request: OpenAiAuthorizationCodeExchange):
        OpenAiLoginResult<OpenAiSubscriptionTokens> = error("No login in these root gates.")
    override suspend fun refreshSubscriptionTokens(refreshToken: String):
        OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("No login in these root gates.")
}
