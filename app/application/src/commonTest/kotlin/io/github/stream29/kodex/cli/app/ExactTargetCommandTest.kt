package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import io.github.stream29.kodex.app.test.RpcFrontendFixture
import io.github.stream29.kodex.app.test.withRpcFrontend
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.rpc.RpcSessionCatalogDependencies
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlin.test.*

/** Gates Application admission; the child, registry, JSON transport and backend are real. */
val exactTargetCommandTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("empty startup and repeated plus selection never allocate or reconstruct the singleton") {
        withCommandApplication {
            assertTrue(application.navigation.value.tabs.isEmpty())
            assertEquals(ApplicationTabSelection.NewSession, application.navigation.value.selection)
            repeat(3) { application.selectNewSession() }
            assertSame(first, application.newSession)
            assertEquals(1, drafts.size)
            assertTrue(backend.services.global.getSessionCatalog(true).isEmpty())
            assertNull(application.submitNewSession(first.composer.state.value.revision))
            assertEquals(0, first.submissions)
        }
    }
    test("duplicate admission rejects before the application mutex and revalidates a queued revision") {
        withCommandApplication {
            val blocker = blockCommands()
            val old = input("old")
            val accepted = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(old) }
            assertFalse(accepted.isCompleted)
            val next = input("newer draft")
            assertNull(application.submitNewSession(next), "A new revision cannot queue behind an accepted submission.")
            assertEquals(0, first.submissions)
            blocker.proceed()
            assertNull(accepted.await(), "Mutation must reject the now-stale revision.")
            assertEquals(0, first.submissions)
            assertTrue(application.navigation.value.tabs.isEmpty())
            assertEquals("newer draft", first.composer.state.value.text)
            assertNotNull(application.submitNewSession(next))
            assertEquals(1, first.submissions)
        }
    }
    test("in-flight duplicates create once then a second valid draft creates another Session") {
        withCommandApplication {
            val gate = commandGate()
            first.beforeSubmit = gate::wait
            val revision = input(" \n first message \n ")
            val accepted = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(revision) }
            gate.entered.await()
            assertNull(application.submitNewSession(revision))
            assertEquals(1, first.submissions)
            gate.proceed.complete(Unit)
            val persisted = assertNotNull(accepted.await())
            assertSame(persisted, application.navigation.value.selectedSession)
            assertEquals(listOf(persisted), application.navigation.value.tabs)
            assertEquals("", first.composer.state.value.text)
            assertEquals(0, first.closes)
            val next = input("second")
            assertNull(application.submitNewSession(revision), "A delayed renderer callback cannot consume the next input.")
            assertEquals("second", first.composer.state.value.text)
            val second = assertNotNull(application.submitNewSession(next))
            assertNotSame(persisted, second)
            assertEquals(listOf(persisted, second), application.navigation.value.tabs)
            assertSame(second, application.navigation.value.selectedSession)
            assertEquals(2, backend.services.global.getSessionCatalog(true).size)
            assertSame(first, application.newSession)
            assertEquals(1, drafts.size)
        }
    }
    for ((label, text) in listOf("spaces" to "   ", "newlines" to "\n\n", "padded" to " \n original \t\n")) {
        test("Application submits nonzero $label text unchanged through real RPC") {
            withCommandApplication {
                val persisted = assertNotNull(application.submitNewSession(input(text)))
                val message = assertIs<StableUserMessage>(backend.services.index.get(
                    persisted.sessionIndex, backend.services.index.getCacheNonce(persisted.sessionIndex), 1,
                ))
                assertEquals(listOf(ContentItem.InputText(text)), message.content)
                assertEquals("", first.composer.state.value.text)
                assertSame(persisted, application.navigation.value.selectedSession)
            }
        }
    }
    test("late success publishes the captured Session but preserves newer input and ABA cwd edits") {
        withCommandApplication {
            val gate = commandGate()
            beforeOpen = gate::wait
            first.updateWorkingDirectory(SystemTemporaryDirectory)
            val original = input("captured text")
            val renderer = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(original) }
            gate.entered.await()
            val later = input("later text")
            first.updateWorkingDirectory(backend.root)
            first.updateWorkingDirectory(SystemTemporaryDirectory)
            assertNull(application.submitNewSession(later))
            renderer.cancelAndJoin()
            gate.proceed.complete(Unit)
            val persisted = application.navigation.first { it.tabs.size == 1 }.tabs.single()
            assertEquals(SystemTemporaryDirectory, persisted.settings.value.cwd)
            assertEquals("later text", first.composer.state.value.text)
            assertEquals(later, first.composer.state.value.revision)
            first.settings.first { it.cwd == SystemTemporaryDirectory }
            val message = assertIs<StableUserMessage>(backend.services.index.get(
                persisted.sessionIndex, backend.services.index.getCacheNonce(persisted.sessionIndex), 1,
            ))
            assertEquals(listOf(ContentItem.InputText("captured text")), message.content)
            assertNull(application.submitNewSession(original))
            assertNotNull(application.submitNewSession(later))
            assertEquals("", first.composer.state.value.text)
            first.settings.first { it.cwd == backend.root }
            assertEquals(2, application.navigation.value.tabs.size)
        }
    }
    test("renderer wait cancellation during accepted work still resets and publishes navigation") {
        withCommandApplication {
            val gate = commandGate()
            first.beforeSubmit = gate::wait
            val revision = input("owned by frontend")
            val chosen = Path(backend.root, "chosen")
            Fs.createDirectories(chosen)
            first.updateWorkingDirectory(chosen)
            first.settings.first { it.cwd == chosen }
            val renderer = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(revision) }
            gate.entered.await()
            renderer.cancelAndJoin()
            assertFailsWith<CancellationException> { renderer.await() }
            assertTrue(application.navigation.value.tabs.isEmpty())
            gate.proceed.complete(Unit)
            val navigation = application.navigation.first { it.tabs.size == 1 }
            assertSame(navigation.tabs.single(), navigation.selectedSession)
            assertEquals("", first.composer.state.value.text)
            first.settings.first { it.cwd == backend.root }
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
            assertEquals(0, first.closes)
            assertTrue(reports.value.isEmpty())
        }
    }
    test("accepted submission survives renderer cancellation even while queued for the mutex") {
        withCommandApplication {
            val blocker = blockCommands()
            val revision = input("queued accepted input")
            val renderer = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(revision) }
            assertFalse(renderer.isCompleted)
            renderer.cancelAndJoin()
            blocker.proceed()
            application.navigation.first { it.tabs.size == 1 }
            assertEquals(1, first.submissions)
            assertEquals("", first.composer.state.value.text)
            assertTrue(reports.value.isEmpty())
        }
    }
    test("cancelled caller before admission does not submit") {
        withCommandApplication {
            val revision = input("untouched")
            val caller = launch(start = CoroutineStart.LAZY) { application.submitNewSession(revision) }
            caller.cancelAndJoin()
            assertEquals(0, first.submissions)
            assertTrue(backend.services.global.getSessionCatalog(true).isEmpty())
            assertEquals("untouched", first.composer.state.value.text)
        }
    }
    test("opening failure after allocation propagates unchanged without retry reset or navigation") {
        withCommandApplication {
            val popup = application.openSessionCatalogPopup()
            val snapshot = application.navigation.value
            val failure = ChildOpeningFailure()
            failOpening = failure
            val revision = input("do not replay")
            val chosen = Path(backend.root, "failed-cwd")
            Fs.createDirectories(chosen)
            first.updateWorkingDirectory(chosen)
            assertSame(failure, runCatching { application.submitNewSession(revision) }.exceptionOrNull())
            assertSame(snapshot, application.navigation.value)
            assertSame(popup, application.popup.value)
            assertEquals("do not replay", first.composer.state.value.text)
            first.settings.first { it.cwd == chosen }
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
            assertEquals(1, first.submissions)
            assertEquals(0, first.closes)
            assertTrue(reports.value.isEmpty(), "A live waiter owns propagation, not a second owner notification.")
            // Model the renderer's existing onOperationFailure outlet.
            report(failure)
            yield()
            assertEquals(listOf(failure), reports.value)
            assertEquals(1, first.submissions)
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
        }
    }
    test("orphan async opening failure uses the unique owner error outlet exactly once") {
        withCommandApplication {
            val gate = commandGate()
            beforeOpen = gate::wait
            val failure = ChildOpeningFailure()
            failOpening = failure
            val revision = input("failure after caller leaves")
            val renderer = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(revision) }
            gate.entered.await() // backend has allocated, registry opening has not succeeded
            renderer.cancelAndJoin()
            assertTrue(reports.value.isEmpty())
            gate.proceed.complete(Unit)
            reports.first { it.isNotEmpty() }
            yield()
            assertEquals(1, reports.value.size)
            assertSame(failure, reports.value.single())
            assertTrue(application.navigation.value.tabs.isEmpty())
            assertEquals("failure after caller leaves", first.composer.state.value.text)
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
            assertEquals(1, first.submissions)
            // A failed command must not cancel the frontend command owner.
            application.selectNewSession()
        }
    }
    test("queued select and close find a persisted target moved by removal") {
        withCommandApplication {
            val preceding = openPersisted()
            val target = openPersisted()
            val following = openPersisted()
            val blocker = blockCommands()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { application.closeTab(preceding) }
            val selecting = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(target) }
            blocker.proceed()
            assertTrue(closing.await())
            assertTrue(selecting.await())
            assertEquals(listOf(target, following), application.navigation.value.tabs)
            assertSame(target, application.navigation.value.selectedSession)
            assertEquals(ApplicationTabSelection.SelectedTab(0), application.navigation.value.selection)
        }
    }
    test("stale exact target never selects closes or renames the same-slot survivor") {
        withCommandApplication {
            val target = openPersisted()
            val survivor = openPersisted()
            val popup = application.openRenameSessionPopup(target)
            val blocker = blockCommands()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { application.closeTab(target) }
            val selecting = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(target) }
            val closingAgain = async(start = CoroutineStart.UNDISPATCHED) { application.closeTab(target) }
            blocker.proceed()
            assertTrue(closing.await())
            assertFalse(selecting.await())
            assertFalse(closingAgain.await())
            assertSame(survivor, application.navigation.value.selectedSession)
            assertFailsWith<IllegalArgumentException> { application.openRenameSessionPopup(target) }
            assertFalse(popup.viewModel.isActive)
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
        }
    }
    test("same name and index cannot admit an equality-equal foreign persisted handle") {
        withCommandApplication {
            val target = openPersisted()
            val other = openPersisted()
            target.rename("same-name")
            other.rename("same-name")
            val foreign = object : PersistedSessionViewModel by target {
                override fun equals(other: Any?): Boolean = other is PersistedSessionViewModel
                override fun hashCode(): Int = 0
            }
            assertTrue(foreign == target)
            assertFalse(application.selectTab(foreign))
            assertFalse(application.closeTab(foreign))
            assertFailsWith<IllegalArgumentException> { application.openRenameSessionPopup(foreign) }
            assertFailsWith<IllegalArgumentException> { application.openWorkingDirectoryPopup(foreign) }
            assertSame(other, application.navigation.value.selectedSession)
        }
    }
    test("ordinary queued selection still belongs to its caller and cancels without selecting") {
        withCommandApplication {
            val target = openPersisted()
            val selected = openPersisted()
            val blocker = blockCommands()
            val selecting = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(target) }
            assertFalse(selecting.isCompleted)
            selecting.cancelAndJoin()
            blocker.proceed()
            assertSame(selected, application.navigation.value.selectedSession)
        }
    }
    test("removed Session and its captured Agent cannot open cwd pickers") {
        withCommandApplication {
            val target = openPersisted()
            val agent = assertNotNull(target.rootAgent.value)
            application.closeTab(target)
            assertFailsWith<IllegalArgumentException> { application.openWorkingDirectoryPopup(target) }
            assertFailsWith<IllegalArgumentException> { application.openWorkingDirectoryPopup(agent) }
        }
    }
    test("shutdown cancels and joins accepted work closes singleton and popup without Stop or notification") {
        withCommandApplication {
            val tab = openPersisted()
            val popup = application.openRenameSessionPopup(tab)
            val gate = commandGate()
            first.beforeSubmit = gate::wait
            val revision = input("cancel local orchestration on shutdown")
            val renderer = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(revision) }
            gate.entered.await()
            application.shutdown() // Must not wait for gate.proceed or deadlock on commandMutex.
            assertFailsWith<CancellationException> { renderer.await() }
            assertEquals(1, first.closes)
            assertEquals(ComposerLifecycle.Closed, first.composer.state.value.lifecycle)
            assertFalse(popup.viewModel.isActive)
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
            assertEquals(PersistedSessionLifecycleState.Closed, tab.lifecycle.value)
            assertNull(tab.rootAgent.value)
            assertTrue(backend.services.global.getSessionCatalog(true).single().isActive)
            assertTrue(reports.value.isEmpty())
            assertFailsWith<IllegalStateException> { application.submitNewSession(revision) }
            assertFailsWith<IllegalStateException> { application.selectNewSession() }
            application.shutdown()
            assertEquals(1, first.closes)
        }
    }
    test("shutdown releases a created registry handle even when submission never published its tab") {
        withCommandApplication {
            val gate = commandGate()
            var opened: PersistedSessionViewModel? = null
            afterOpen = {
                opened = it
                gate.wait()
            }
            val revision = input("allocated but not appended")
            val renderer = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(revision) }
            gate.entered.await()
            renderer.cancelAndJoin()
            assertTrue(application.navigation.value.tabs.isEmpty())
            val handle = assertNotNull(opened)
            assertNotNull(handle.rootAgent.value)
            application.shutdown()
            assertNull(handle.rootAgent.value)
            assertEquals(PersistedSessionLifecycleState.Closed, handle.lifecycle.value)
            assertEquals(ComposerLifecycle.Closed, first.composer.state.value.lifecycle)
            assertEquals(1, first.closes)
            assertTrue(application.navigation.value.tabs.isEmpty())
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
            assertTrue(reports.value.isEmpty())
            assertFalse(gate.proceed.isCompleted, "Cleanup cannot require the renderer's business gate.")
        }
    }
    test("frontend owner termination cancels orphan work and closes popup and singleton") {
        withCommandApplication {
            val tab = openPersisted()
            val popup = application.openRenameSessionPopup(tab)
            val gate = commandGate()
            first.beforeSubmit = gate::wait
            val revision = input("owner shutdown")
            val renderer = async(start = CoroutineStart.UNDISPATCHED) { application.submitNewSession(revision) }
            gate.entered.await()
            renderer.cancelAndJoin()
            frontendOwner.cancelAndJoin()
            assertEquals(1, first.closes)
            assertEquals(ComposerLifecycle.Closed, first.composer.state.value.lifecycle)
            assertFalse(popup.viewModel.isActive)
            assertEquals(listOf(tab), application.navigation.value.tabs)
            assertTrue(reports.value.isEmpty())
            assertFailsWith<IllegalStateException> { application.selectNewSession() }
        }
    }
}

private class CommandGate {
    val entered = CompletableDeferred<Unit>()
    val proceed = CompletableDeferred<Unit>()
    suspend fun wait() { entered.complete(Unit); proceed.await() }
}

/** Observation only: every successful submission still executes the real child/RPC path. */
private class ObservedDraft(private val delegate: NewSessionViewModel) : NewSessionViewModel by delegate {
    var submissions = 0
    var closes = 0
    var beforeSubmit: suspend () -> Unit = {}
    override suspend fun submit(expectedRevision: Long): PersistedSessionViewModel? {
        submissions++
        beforeSubmit()
        return delegate.submit(expectedRevision)
    }
    override fun close() { closes++; delegate.close() }
}

private class ChildOpeningFailure : IllegalStateException("persisted child opening failed")

private class CommandApplicationFixture(
    val backend: RpcFrontendFixture,
    scope: CoroutineScope,
) : CoroutineScope by scope {
    val frontendOwner = SupervisorJob(scope.coroutineContext[Job])
    val reports = MutableStateFlow<List<Throwable>>(emptyList())
    fun report(error: Throwable) { reports.update { it + error } }
    private val frontendScope = CoroutineScope(scope.coroutineContext + frontendOwner +
        CoroutineExceptionHandler { _, failure -> report(failure) })
    val drafts = mutableListOf<ObservedDraft>()
    private val gates = mutableListOf<CommandGate>()
    fun commandGate(): CommandGate = CommandGate().also { gates += it }
    fun unblockCleanup() { gates.forEach { it.proceed.complete(Unit) } }
    var failOpening: Throwable? = null
    var beforeOpen: suspend () -> Unit = {}
    var afterOpen: suspend (PersistedSessionViewModel) -> Unit = {}
    private var forkGate: CommandGate? = null
    private val registry = object : PersistedSessionViewModelRegistry by backend.sessions {
        override suspend fun open(sessionIndex: Int): PersistedSessionViewModel {
            beforeOpen()
            failOpening?.let { throw it }
            return backend.sessions.open(sessionIndex).also { afterOpen(it) }
        }
        override suspend fun fork(sessionIndex: Int): Int {
            forkGate?.wait()
            return backend.sessions.fork(sessionIndex)
        }
    }
    private val actualDrafts = DefaultNewSessionViewModelFactory(
        backend.views, registry, backend.global, frontendScope,
    )
    val application = ApplicationViewModelImpl(
        sessions = registry,
        newSessionFactory = NewSessionViewModelFactory { arguments ->
            ObservedDraft(actualDrafts.create(arguments)).also { drafts += it }
        },
        catalogFactory = SessionCatalogViewModelFactory { dependencies, interactions ->
            DefaultSessionCatalogViewModel(frontendScope, dependencies, interactions)
        },
        catalogDependencies = RpcSessionCatalogDependencies(backend.services.global),
        settingsFactory = SettingsViewModelFactory { error("These command gates do not open Settings.") },
        loginFactory = OpenAiLoginViewModelFactory { error("These command gates do not open Login.") },
        createDirectoryPicker = { error("These command gates do not open a directory picker.") },
        newSessionArguments = NewSessionViewModelArguments(backend.root),
        ownerScope = frontendScope,
    )
    val first: ObservedDraft get() = drafts.single()
    suspend fun input(text: String): Long {
        first.composer.update(text, text.length)
        return first.composer.state.value.revision
    }
    suspend fun openPersisted(): PersistedSessionViewModel =
        application.openSession(backend.services.global.createSession(backend.root))

    suspend fun blockCommands(): CommandBlocker {
        val index = backend.services.global.createSession(backend.root)
        val gate = commandGate().also { forkGate = it }
        val operation = async(start = CoroutineStart.UNDISPATCHED) { application.forkSession(index) }
        gate.entered.await()
        return CommandBlocker(gate, operation) { forkGate = null }
    }
}

private class CommandBlocker(
    private val gate: CommandGate,
    private val operation: Deferred<Int>,
    private val finished: () -> Unit,
) {
    suspend fun proceed() { gate.proceed.complete(Unit); operation.await(); finished() }
}

private suspend fun withCommandApplication(block: suspend CommandApplicationFixture.() -> Unit) {
    withRpcFrontend {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val fixture = CommandApplicationFixture(this@withRpcFrontend, this)
            try { fixture.block() }
            finally {
                fixture.unblockCleanup()
                withContext(NonCancellable) {
                    fixture.application.shutdown()
                    fixture.frontendOwner.cancelAndJoin()
                }
            }
        }
    }
}
