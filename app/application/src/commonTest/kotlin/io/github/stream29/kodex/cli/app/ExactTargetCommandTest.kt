package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import io.github.stream29.kodex.app.test.RpcFrontendFixture
import io.github.stream29.kodex.app.test.testSettings
import io.github.stream29.kodex.app.test.withRpcFrontend
import io.github.stream29.kodex.cli.rpc.RpcSessionCatalogDependencies
import kotlinx.coroutines.*
import kotlin.test.*

/** Gates real Application commandMutex admission; underlying drafts use the real JSON/RPC fixture. */
val exactTargetCommandTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("queued duplicate materialization creates once and preserves unselected slot and popup cleanup") {
        withCommandApplication {
            val target = first
            val selected = application.createNewSessionTab()
            val popup = application.openRenameSessionPopup(target)
            val snapshot = application.navigation.value
            val gate = commandGate()
            target.beforeMaterialize = gate::wait
            val accepted = async(start = CoroutineStart.UNDISPATCHED) {
                application.materializeNewSession(target)
            }
            gate.entered.await()
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
                application.materializeNewSession(target)
            }
            assertFalse(duplicate.isCompleted)
            assertEquals(1, target.materializations)
            gate.proceed.complete(Unit)
            val persisted = assertNotNull(accepted.await())
            assertNull(duplicate.await())
            assertEquals(1, target.materializations)
            assertEquals(1, target.closes)
            assertEquals(snapshot.tabs.size, application.navigation.value.tabs.size)
            assertEquals(snapshot.selectedIndex, application.navigation.value.selectedIndex)
            assertSame(persisted, application.navigation.value.tabs.first())
            assertSame(selected, application.navigation.value.selected)
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
            assertFalse(popup.viewModel.isActive)
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
        }
    }

    test("queued selection and materialization find a target moved by closing a preceding tab") {
        withCommandApplication {
            val preceding = first
            val target = application.createNewSessionTab() as ObservedDraft
            application.createNewSessionTab()
            val blocker = blockCommands()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { application.closeTab(preceding) }
            val selecting = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(target) }
            val materializing = async(start = CoroutineStart.UNDISPATCHED) {
                application.materializeNewSession(target)
            }
            assertFalse(closing.isCompleted)
            assertFalse(selecting.isCompleted)
            assertFalse(materializing.isCompleted)
            assertEquals(0, target.materializations)
            blocker.proceed()
            assertTrue(closing.await())
            assertTrue(selecting.await())
            val persisted = assertNotNull(materializing.await())
            assertSame(persisted, application.navigation.value.tabs[0])
            assertSame(persisted, application.navigation.value.selected)
            assertEquals(0, application.navigation.value.selectedIndex)
            assertEquals(2, application.navigation.value.tabs.size)
            assertEquals(1, target.materializations)
        }
    }

    test("closed queued target is stale and never selects or materializes the same-slot replacement") {
        withCommandApplication {
            val target = first
            val survivor = application.createNewSessionTab()
            val popup = application.openRenameSessionPopup(target)
            val blocker = blockCommands()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { application.closeTab(target) }
            val selecting = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(target) }
            val materializing = async(start = CoroutineStart.UNDISPATCHED) {
                application.materializeNewSession(target)
            }
            assertFalse(materializing.isCompleted)
            blocker.proceed()
            assertTrue(closing.await())
            assertFalse(selecting.await())
            assertNull(materializing.await())
            assertEquals(0, target.materializations)
            assertEquals(1, target.closes)
            assertSame(survivor, application.navigation.value.selected)
            assertEquals(listOf(survivor), application.navigation.value.tabs)
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
            assertFalse(popup.viewModel.isActive)
        }
    }

    test("equal names and structural equality cannot admit a foreign instance or select its twin") {
        withCommandApplication {
            val target = first
            val twin = application.createNewSessionTab() as ObservedDraft
            val foreign = ObservedDraft(backend.draft("same-name"))
            try {
                assertEquals(target.name.value, twin.name.value)
                assertEquals(target, twin) // Deliberately equality-equal test handles.
                assertEquals(target, foreign)
                val blocker = blockCommands()
                val selecting = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(target) }
                val staleSelect = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(foreign) }
                val staleCreate = async(start = CoroutineStart.UNDISPATCHED) {
                    application.materializeNewSession(foreign)
                }
                blocker.proceed()
                assertTrue(selecting.await())
                assertSame(target, application.navigation.value.selected)
                assertFalse(staleSelect.await())
                assertNull(staleCreate.await())
                assertEquals(0, foreign.materializations)
                assertEquals(0, twin.materializations)
            } finally { foreign.close() }
        }
    }

    test("caller cancellation while queued cannot materialize or select after admission resumes") {
        withCommandApplication {
            val target = first
            val selected = application.createNewSessionTab()
            val blocker = blockCommands()
            val selecting = async(start = CoroutineStart.UNDISPATCHED) { application.selectTab(target) }
            val materializing = async(start = CoroutineStart.UNDISPATCHED) {
                application.materializeNewSession(target)
            }
            assertFalse(selecting.isCompleted)
            assertFalse(materializing.isCompleted)
            selecting.cancelAndJoin()
            materializing.cancelAndJoin()
            blocker.proceed()
            assertFailsWith<CancellationException> { selecting.await() }
            assertFailsWith<CancellationException> { materializing.await() }
            assertEquals(0, target.materializations)
            assertSame(selected, application.navigation.value.selected)
            assertEquals(0, target.closes)
        }
    }

    test("cancelled in-flight local materialization releases mutex without consuming the draft") {
        withCommandApplication {
            val target = first
            val snapshot = application.navigation.value
            val gate = commandGate()
            target.beforeMaterialize = gate::wait
            val materializing = async(start = CoroutineStart.UNDISPATCHED) {
                application.materializeNewSession(target)
            }
            gate.entered.await()
            materializing.cancelAndJoin()
            assertFailsWith<CancellationException> { materializing.await() }
            assertSame(snapshot, application.navigation.value)
            assertEquals(0, target.closes)
            assertTrue(backend.services.global.getSessionCatalog(true).isEmpty())
            target.beforeMaterialize = {}
            assertNotNull(application.materializeNewSession(target))
            assertEquals(2, target.materializations)
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
        }
    }

    test("real child-opening failure after allocation escapes and retains draft and backend without recreation") {
        withCommandApplication {
            val target = first
            val popup = application.openRenameSessionPopup(target)
            val snapshot = application.navigation.value
            val failure = ChildOpeningFailure(Unit)
            failOpening = failure
            val outcome = runCatching { application.materializeNewSession(target) }
            assertSame(failure, outcome.exceptionOrNull())
            assertSame(snapshot, application.navigation.value)
            assertSame(popup, application.popup.value)
            assertTrue(popup.viewModel.isActive)
            assertEquals(0, target.closes)
            val created = backend.services.global.getSessionCatalog(true).single().sessionIndex
            assertEquals(1, target.materializations)
            failOpening = null
            val persisted = assertNotNull(application.materializeNewSession(target))
            assertEquals(created, persisted.sessionIndex)
            assertEquals(1, backend.services.global.getSessionCatalog(true).size)
            assertNull(application.materializeNewSession(target))
            assertEquals(2, target.materializations)
        }
    }

    test("serialized shutdown drains admitted work then rejects queued and later commands") {
        withCommandApplication {
            val target = first
            val popup = application.openRenameSessionPopup(target)
            val gate = commandGate()
            target.beforeMaterialize = gate::wait
            val materializing = async(start = CoroutineStart.UNDISPATCHED) {
                application.materializeNewSession(target)
            }
            gate.entered.await()
            val shutdown = async(start = CoroutineStart.UNDISPATCHED) { application.shutdown() }
            val late = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { application.selectTab(target) }
            }
            assertFalse(shutdown.isCompleted)
            assertFalse(late.isCompleted)
            gate.proceed.complete(Unit)
            val persisted = assertNotNull(materializing.await())
            shutdown.await()
            assertIs<IllegalStateException>(late.await().exceptionOrNull())
            assertNull(persisted.rootAgent.value)
            assertEquals(PersistedSessionLifecycleState.Closed, persisted.lifecycle.value)
            assertEquals(ApplicationPopupState.Closed, application.popup.value)
            assertFalse(popup.viewModel.isActive)
            assertEquals(1, target.closes)
            assertFailsWith<IllegalStateException> { application.materializeNewSession(target) }
            assertFailsWith<IllegalStateException> { application.createNewSessionTab() }
            application.shutdown()
            assertTrue(backend.services.global.getSessionCatalog(true).single().isActive)
        }
    }
}

private class CommandGate {
    val entered = CompletableDeferred<Unit>()
    val proceed = CompletableDeferred<Unit>()
    suspend fun wait() { entered.complete(Unit); proceed.await() }
}

/** Test-only observation seam; all state, children, edits and allocation remain in the real draft. */
private class ObservedDraft(
    private val delegate: NewSessionViewModel,
) : NewSessionViewModel by delegate {
    var materializations = 0
    var closes = 0
    var beforeMaterialize: suspend () -> Unit = {}
    override suspend fun materialize(): PersistedSessionViewModel {
        materializations++
        beforeMaterialize()
        return delegate.materialize()
    }
    override fun close() { closes++; delegate.close() }
    override fun equals(other: Any?): Boolean = other is ObservedDraft
    override fun hashCode(): Int = 0
}

private class ChildOpeningFailure(val marker: Unit) : IllegalStateException("persisted child opening failed")

private class CommandApplicationFixture(
    val backend: RpcFrontendFixture,
    scope: CoroutineScope,
) : CoroutineScope by scope {
    val drafts = mutableListOf<ObservedDraft>()
    private val gates = mutableListOf<CommandGate>()
    fun commandGate(): CommandGate = CommandGate().also { gates += it }
    fun unblockCleanup() { gates.forEach { it.proceed.complete(Unit) } }
    var failOpening: Throwable? = null
    private var forkGate: CommandGate? = null
    private val registry = object : PersistedSessionViewModelRegistry by backend.sessions {
        override suspend fun open(sessionIndex: Int): PersistedSessionViewModel {
            failOpening?.let { throw it }
            return backend.sessions.open(sessionIndex)
        }
        override suspend fun fork(sessionIndex: Int): Int {
            forkGate?.wait()
            return backend.sessions.fork(sessionIndex)
        }
    }
    private val actualDrafts = io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory(
        backend.views, registry, backend.models, this,
    )
    val application = ApplicationViewModelImpl(
        sessions = registry,
        newSessionFactory = NewSessionViewModelFactory { arguments ->
            ObservedDraft(actualDrafts.create(arguments)).also { drafts += it }
        },
        catalogFactory = SessionCatalogViewModelFactory { dependencies, interactions ->
            DefaultSessionCatalogViewModel(this, dependencies, interactions)
        },
        catalogDependencies = RpcSessionCatalogDependencies(backend.services.global),
        settingsFactory = SettingsViewModelFactory { error("These command gates do not open Settings.") },
        loginFactory = OpenAiLoginViewModelFactory { error("These command gates do not open Login.") },
        createDirectoryPicker = { error("These command gates do not open a directory picker.") },
        newSessionArguments = { NewSessionViewModelArguments("same-name", testSettings("", backend.root)) },
        ownerScope = this,
    )
    val first: ObservedDraft get() = drafts.first()

    suspend fun blockCommands(): CommandBlocker {
        val index = backend.services.global.createSession(testSettings("", backend.root))
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
    suspend fun proceed() {
        gate.proceed.complete(Unit)
        operation.await()
        finished()
    }
}

private suspend fun withCommandApplication(block: suspend CommandApplicationFixture.() -> Unit) {
    withRpcFrontend {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val fixture = CommandApplicationFixture(this@withRpcFrontend, this)
            try { fixture.block() }
            finally {
                fixture.unblockCleanup()
                withContext(NonCancellable) { fixture.application.shutdown() }
            }
        }
    }
}
