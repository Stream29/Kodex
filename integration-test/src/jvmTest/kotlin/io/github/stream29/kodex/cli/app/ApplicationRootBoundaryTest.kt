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
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.settings.createOpenAiLoginViewModel
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.createSettingsViewModel
import io.github.stream29.kodex.app.test.*
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.session.RpcPersistedSessionViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.rpc.models.OAuthTarget
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
                val singleton = application.newSession
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
                        clickBoundaryText(boundarySnapshot("[+]"), "[+]")
                        // Check the final exact selection after releasing the
                        // captured command; do not impose a new root concurrency policy.
                        proceed.complete(Unit)
                        withTimeout(5.seconds) { finished.await() }
                        awaitBoundaryCondition { application.navigation.value.selection == ApplicationTabSelection.NewSession }
                        yield()
                        if (operation != "cancel-revert") awaitBoundaryCondition { failures.size == 1 }
                        else awaitBoundaryCondition { reverts == 1 && !draw().render(AnsiLevel.NONE, false).contains("Revert and edit") }
                        assertNull(application.navigation.value.selectedSession)
                        assertSame(singleton, application.newSession)
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
                assertSame(selected, application.navigation.value.selectedSession)
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
                val selected = application.navigation.value.selectedSession
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
                    assertSame(selected, application.navigation.value.selectedSession)
                    assertEquals(1, calls)
                    assertEquals(1, services.global.getSessionCatalog(true).size)
                }
            }
        }
    }
    test("equal Session twins mount the actual root, route genuine selection and delete without losing exact survivor") {
        withBoundaryRoot(equalSessions = true) {
            val first = application.openSession(historyIndex)
            val middleIndex = services.global.createSession(root)
            val middle = application.openSession(middleIndex)
            middle.rename("Middle twin")
            middle.name.first { it == "Middle twin" }
            val lastIndex = services.global.createSession(root)
            val selected = application.openSession(lastIndex)
            selected.rename("Selected twin")
            selected.name.first { it == "Selected twin" }
            assertEquals(first, middle)
            assertEquals(middle, selected)
            assertNotSame(first, selected)
            runBoundaryMosaic {
                setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                clickBoundaryText(boundarySnapshot("[Root history]"), "[Root history]")
                awaitBoundaryCondition { application.navigation.value.selectedSession === first }
                clickBoundaryText(boundarySnapshot("[Selected twin]"), "[Selected twin]")
                awaitBoundaryCondition { application.navigation.value.selectedSession === selected }
                clickBoundaryText(boundarySnapshot("[Middle twin]"), "[Middle twin]", secondary = true)
                clickBoundaryText(boundarySnapshot("Delete"), "Delete")
                clickBoundaryText(boundarySnapshot("Delete Middle twin?"), "[Delete]")
                awaitBoundaryCondition { application.navigation.value.tabs.none { it === middle } }
                assertSame(selected, application.navigation.value.selectedSession,
                    "Equality-based indexOf incorrectly selects the first surviving twin.")
                assertTrue(application.navigation.value.tabs.any { it === first })
                assertFalse(application.selectTab(middle), "Departed exact handle is not its surviving twin.")
                clickBoundaryText(boundarySnapshot("[Root history]"), "[Root history]")
                awaitBoundaryCondition { application.navigation.value.selectedSession === first }
                assertFalse("Middle twin" in boundarySnapshot("[Selected twin]"))
            }
        }
    }
}

private class BoundarySession(
    val original: PersistedSessionViewModel,
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

internal class BoundaryRoot(
    val root: Path,
    val services: RpcServices,
    val application: ApplicationViewModel,
    val global: RpcGlobalSettings,
    val reports: StateFlow<List<Throwable>>,
    scope: CoroutineScope,
    val historyIndex: Int,
) {
    val newLineKey = MutableStateFlow(NewLineKey.ShiftEnter)
    val sidebar = createSidebarSettingsViewModel(global, scope, initialized = true)
}

internal suspend fun withBoundaryRoot(
    decorate: (RpcClient) -> RpcClient = { it },
    equalSessions: Boolean = false,
    rejectHistoryRequest: Boolean = false,
    seedHistory: Boolean = true,
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { testAnswer() },
    observeDefaultsAfter: Deferred<Unit>? = null,
    block: suspend BoundaryRoot.() -> Unit,
) = withContext(rootBoundaryDispatcher) { withTimeout(45.seconds) {
    val root = Path(SystemTemporaryDirectory, "kodex-root-boundary-${Random.nextLong()}")
    val client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { flow { response() } }
    }
    var rootFailure: Throwable? = null
    try {
        val home = prepareKodexHome(root)
        var homeFailure: Throwable? = null
        try {
            val historyIndex = if (seedHistory) seedTestHistory(root, "Root history", mapOf(
                1 to StableUserMessage(listOf(ContentItem.InputText("root-history-entry"))),
            )) else -1
            withBackendServices(
                root, Path(root, "codex"), Path(root, "agents"),
                defaults = defaultBackendSettings().let {
                    it.copy(
                        sessionTitle = SessionTitleSettings(false),
                        newSession = it.newSession.copy(model = OpenAiModelId("test-model")),
                    )
                },
                createClient = { client }, createLoginClient = { BoundaryLoginClient },
            ) { backend ->
                withInMemoryRpc(backend::register) { raw ->
                    val owner = SupervisorJob(coroutineContext[Job])
                    val reports = MutableStateFlow<List<Throwable>>(emptyList())
                    val report: (Throwable) -> Unit = { error -> reports.update { it + error } }
                    val local = CoroutineScope(
                        coroutineContext + owner + CoroutineExceptionHandler { _, error -> report(error) },
                    )
                    var ownedViews: RpcSessionViews? = null
                    var ownedSessions: DefaultPersistedSessionViewModelRegistry? = null
                    var ownedGlobal: RpcGlobalSettings? = null
                    var ownedApplication: ApplicationViewModel? = null
                    var failure: Throwable? = null
                    try {
                        val services = RpcServices(decorate(RestoringRpcClient(raw)))
                        val models = MutableStateFlow(services.global.getModels())
                        val views = RpcSessionViews(local, services, models, reportUnhandledError = report)
                            .also { ownedViews = it }
                        val actualSessions = DefaultPersistedSessionViewModelRegistry(views, models, local)
                            .also { ownedSessions = it }
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
                        val defaultsRpc = object : io.github.stream29.kodex.rpc.contract.GlobalRpc by services.global {
                            override fun getSettingsFlow() = flow {
                                observeDefaultsAfter?.await()
                                emitAll(services.global.getSettingsFlow())
                            }
                        }
                        val global = RpcGlobalSettings.open(defaultsRpc, local, 120).also { ownedGlobal = it }
                        val directoryPicker = { path: Path -> createDirectoryPickerViewModel(path, local) }
                        val app = createApplicationViewModel(
                            sessions, DefaultNewSessionViewModelFactory(views, sessions, global, local),
                            SessionCatalogViewModelFactory { dependencies, interactions ->
                                DefaultSessionCatalogViewModel(local, dependencies, interactions)
                            },
                            RpcSessionCatalogDependencies(services.global),
                            SettingsViewModelFactory { arguments ->
                                val source = when (val target = arguments.target) {
                                    null -> UnavailableSessionSettingsDataSource
                                    is BoundarySession -> RpcSessionSettingsSource(
                                        (target.original as RpcPersistedSessionViewModel).view, local,
                                    )
                                    else -> error("Unknown boundary Session.")
                                }
                                createSettingsViewModel(
                                    arguments.initialPage, RpcGlobalEditor(global, services.global, local),
                                    createSessionSettingsViewModel(
                                        SessionSettingsDependencies(source, models, directoryPicker), local,
                                    ),
                                    RpcNewSessionSettings(global, local),
                                )
                            },
                            OpenAiLoginViewModelFactory {
                                createOpenAiLoginViewModel(
                                    OpenAiLoginDependencies {
                                        startRpcOAuth(services.global, OAuthTarget.OpenAi(global.settings.value.authSource), local)
                                    }, local,
                                )
                            },
                            directoryPicker,
                            NewSessionViewModelArguments(root),
                            local,
                        ).also { ownedApplication = it }
                        BoundaryRoot(root, services, app, global, reports, local, historyIndex).block()
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        cleanupBoundary(failure,
                            { ownedApplication?.close() },
                            { ownedApplication?.shutdown() },
                            { ownedSessions?.shutdown() },
                            { ownedViews?.close() },
                            { ownedGlobal?.close() },
                            { owner.cancelAndJoin() },
                            { ownedGlobal?.join() },
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            homeFailure = error
            throw error
        } finally { cleanupBoundary(homeFailure, { home.closeAndJoin() }) }
    } catch (error: Throwable) {
        rootFailure = error
        throw error
    } finally { cleanupBoundary(rootFailure, { deleteTestDirectory(root) }) }
} }

private suspend fun cleanupBoundary(primary: Throwable?, vararg actions: suspend () -> Unit) {
    var failure = primary
    withContext(NonCancellable) {
        for (action in actions) {
            try { withTimeout(10.seconds) { action() } }
            catch (error: Throwable) {
                val original = failure
                if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
            }
        }
    }
    if (primary == null) failure?.let { throw it }
}

internal suspend fun runBoundaryMosaic(block: suspend TestMosaic<String>.() -> Unit) = runMosaicTest {
    state.size.value = Terminal.Size(120, 40)
    try { block() } finally { cancel() }
}

internal suspend fun TestMosaic<String>.boundarySnapshot(expected: String): String {
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

internal suspend fun TestMosaic<String>.awaitBoundaryCondition(condition: () -> Boolean) = withTimeout(5.seconds) {
    while (!condition()) {
        currentCoroutineContext().ensureActive()
        // A captured failure list is deliberately not Compose state and need
        // not redraw. Do not wait forever for a frame after the condition changed.
        withTimeoutOrNull(50.milliseconds) { awaitSnapshot() }
        yield()
    }
}

internal suspend fun TestMosaic<String>.clickBoundaryText(snapshot: String, label: String, secondary: Boolean = false) {
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
