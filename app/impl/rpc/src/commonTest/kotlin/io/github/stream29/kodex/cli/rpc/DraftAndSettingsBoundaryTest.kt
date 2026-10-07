@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.cli.settings.openCliFrontendSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.BackendSettings
import kotlinx.rpc.RpcClient
import kotlinx.rpc.RpcCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Actual draft/source/component and actual global/default queues, not fake CAS receipts. */
val draftAndSettingsBoundaryTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("supplied draft parent cancellation joins Composer observers and rejects local edits") {
        frontend {
            val parent = Job(coroutineContext[Job])
            val draft = RpcSessionDraft(KodexAgentSettings(OpenAiModelId("test-model")), views,
                CoroutineScope(coroutineContext + parent))
            draft.composer.update("retained")
            parent.cancelAndJoin() // Deliberately no draft.close().
            assertEquals(ComposerLifecycle.Closed, draft.composer.state.value.lifecycle)
            assertTrue(parent.children.none(), "Parent join must include all three Composer observers.")
            assertFalse(draft.editable.value)
            assertFailsWith<IllegalStateException> { draft.composer.update("late") }
            assertFailsWith<IllegalStateException> { draft.edit { it.copy(instructions = "late") } }
            assertTrue(services.global.getSessionCatalog(true).isEmpty())
        }
    }
    test("explicit draft close only closes its child and leaves borrowed backend and sibling alive") {
        val running = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(response = {
            running.complete(Unit)
            finish.await()
            emit(ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)))
        }) {
            val parent = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + parent)
            val first = RpcSessionDraft(KodexAgentSettings(OpenAiModelId("test-model")), views, scope)
            val sibling = RpcSessionDraft(KodexAgentSettings(OpenAiModelId("test-model")), views, scope)
            try {
                val index = services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
                val persisted = views.open(index)
                persisted.current().appendUserMessage(listOf(ContentItem.InputText("backend turn")))
                val turn = async { persisted.current().resume() }
                try {
                    withTimeout(5.seconds) { running.await() }
                    first.close()
                    assertTrue(parent.isActive)
                    assertEquals(ComposerLifecycle.Closed, first.composer.state.value.lifecycle)
                    sibling.composer.update("still editable")
                    sibling.edit { it.copy(instructions = "still local") }
                    assertTrue(services.runtime.getRunningTurn(index), "Local draft close must not Stop borrowed backend.")
                    finish.complete(Unit)
                    withTimeout(5.seconds) { turn.await() }
                    withTimeout(5.seconds) { services.runtime.getRunningTurnFlow(index).first { !it } }
                    assertEquals(1, services.global.getSessionCatalog(true).size)
                } finally {
                    finish.complete(Unit)
                    turn.cancelAndJoin()
                }
            } finally { finish.complete(Unit); parent.cancelAndJoin() }
        }
    }
    for (sameField in listOf(false, true)) {
        test("delayed real source observer preserves unrelated fields and rejects same-field conflict=$sameField") {
            frontend {
                val draft = RpcSessionDraft(KodexAgentSettings(OpenAiModelId("test-model")), views, this)
                // Source observations cannot execute until explicitly released. Initial snapshot is real.
                val scheduler = TestCoroutineScheduler()
                val paused = Job(coroutineContext[Job])
                val source = RpcDraftSettingsSource(draft,
                    CoroutineScope(coroutineContext + paused + StandardTestDispatcher(scheduler)))
                val vmOwner = Job(coroutineContext[Job])
                val vm = createSessionSettingsViewModel(
                    SessionSettingsDependencies(source, MutableStateFlow(emptyList())),
                    CoroutineScope(coroutineContext + vmOwner + UnconfinedTestDispatcher(scheduler)),
                )
                try {
                    val baseline = assertIs<SessionSettingsState.Available>(vm.state.value).snapshot
                    draft.edit { current ->
                        current.copy(
                            model = if (sameField) OpenAiModelId("competing") else current.model,
                            serviceTier = ServiceTier.Fast, instructions = "runtime-owned", threadName = "exact title",
                        )
                    }
                    assertEquals(baseline.revision,
                        assertIs<SessionSettingsDataState.Available>(source.state.value).snapshot.revision)
                    vm.updateModel(baseline.revision, OpenAiModelId("requested"))
                    // The actual VM FIFO executes on Unconfined; the actual source/draft mutex does the write.
                    assertEquals(OpenAiModelId(if (sameField) "competing" else "requested"), draft.settings.value.model)
                    assertEquals(ServiceTier.Fast, draft.settings.value.serviceTier)
                    assertEquals("runtime-owned", draft.settings.value.instructions)
                    assertEquals("exact title", draft.settings.value.threadName)
                    assertTrue(services.global.getSessionCatalog(true).isEmpty())
                    vm.close()
                    vm.updateModel(baseline.revision, OpenAiModelId("closed"))
                    assertNotEquals(OpenAiModelId("closed"), draft.settings.value.model)
                } finally {
                    vm.close(); source.close(); draft.close()
                    vmOwner.cancelAndJoin()
                    // Don't wait on the paused dispatcher: cancellation completion needs its scheduler.
                    paused.cancel()
                    scheduler.runCurrent()
                    paused.join()
                }
            }
        }
    }
    for (defaults in listOf(false, true)) {
        test("command-local cancellation ends actual ${if (defaults) "defaults" else "global"} admission with parent alive") {
            frontend {
                val entered = CompletableDeferred<Unit>()
                val cancelCommand = CompletableDeferred<Unit>()
                var writes = 0
                val rpc = object : GlobalRpc by services.global {
                    override suspend fun compareAndSetSettings(
                        expect: BackendSettings, update: BackendSettings,
                    ): Boolean {
                        writes++
                        entered.complete(Unit)
                        cancelCommand.await()
                        throw CancellationException("one command cancelled")
                    }
                }
                val global = RpcGlobalSettings.open(rpc, openCliFrontendSettings(home), this, 80)
                val page = if (defaults) null else RpcGlobalEditor(global, rpc, this)
                val defaultPage = if (defaults) RpcNewSessionSettings(global, this) else null
                try {
                    if (defaults) defaultPage!!.updateModel(defaultPage.state.value.revision, OpenAiModelId("cancelled"))
                    else page!!.sessionTitleSettings.setEnabled(true)
                    withTimeout(5.seconds) { entered.await() }
                    cancelCommand.complete(Unit)
                    // Both frontend and workers share this fixture's serialized dispatcher.
                    yield(); yield()
                    assertTrue(coroutineContext[Job]!!.isActive)
                    assertFalse(global.operationFailure.value, "Cancellation itself is not ordinary failure.")
                    if (defaults) defaultPage!!.updateModel(defaultPage.state.value.revision, OpenAiModelId("must-reject"))
                    else page!!.sessionTitleSettings.setEnabled(true)
                    withTimeout(5.seconds) { global.operationFailure.first { it } }
                    assertEquals(1, writes, "Dead consumer must reject, not falsely admit a later write.")
                    assertNotEquals(OpenAiModelId("must-reject"), services.global.getSettings().newSession.model)
                } finally {
                    cancelCommand.complete(Unit)
                    page?.close(); defaultPage?.close(); global.close(); global.join()
                }
            }
        }
    }
    for (throughVm in listOf(false, true)) {
        test("materialize makes queued actual draft ${if (throughVm) "Settings VM" else "source"} update nonwritable without failure") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            frontend(decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "createSession") {
                            entered.complete(Unit)
                            release.await()
                        }
                        return original.call(call)
                    }
                }
            }) {
                val initial = KodexAgentSettings(OpenAiModelId("test-model"))
                val draft = RpcSessionDraft(initial, views, this)
                val source = RpcDraftSettingsSource(draft, this)
                val outcome = CompletableDeferred<Result<Boolean>>()
                val reports = mutableListOf<Throwable>()
                val scheduler = TestCoroutineScheduler()
                val vmOwner = Job(coroutineContext[Job])
                // The decorator records the real source outcome; it supplies no CAS behavior.
                val observedSource = object : SessionSettingsDataSource by source {
                    override suspend fun tryUpdateConfiguration(
                        expectedRevision: Long, configuration: SessionSettingsConfiguration,
                    ): Boolean {
                        val result = runCatching { source.tryUpdateConfiguration(expectedRevision, configuration) }
                        outcome.complete(result)
                        return result.getOrThrow()
                    }
                }
                val vm = createSessionSettingsViewModel(
                    SessionSettingsDependencies(observedSource, MutableStateFlow(emptyList()),
                        reportUnhandledError = { failure, _ -> reports += failure }),
                    CoroutineScope(coroutineContext + vmOwner + UnconfinedTestDispatcher(scheduler)),
                )
                val allocating = async(start = CoroutineStart.UNDISPATCHED) { draft.materialize() }
                var updating: Deferred<Unit>? = null
                try {
                    withTimeout(5.seconds) { entered.await() } // Original materialize mutex is held.
                    val baseline = assertIs<SessionSettingsDataState.Available>(source.state.value).snapshot
                    if (throughVm) vm.updateModel(baseline.revision, OpenAiModelId("must-reject"))
                    else updating = async<Unit>(start = CoroutineStart.UNDISPATCHED) {
                        outcome.complete(runCatching {
                            source.tryUpdateConfiguration(baseline.revision,
                                baseline.configuration.copy(model = OpenAiModelId("must-reject")))
                        })
                    }
                    assertFalse(outcome.isCompleted, "The actual source must be waiting on the original draft lock.")
                    assertTrue(vmOwner.isActive) // Neither VM nor source closes to manufacture rejection.
                    assertEquals(initial, draft.settings.value)
                    release.complete(Unit)
                    val persisted = allocating.await()
                    val result = withTimeout(5.seconds) { outcome.await() }
                    scheduler.runCurrent() // Drain the actual VM reporter continuation, if any.
                    assertFalse(result.getOrThrow(), "Nonwritable admission is false, not IllegalStateException.")
                    assertTrue(reports.isEmpty())
                    assertTrue(vmOwner.isActive)
                    assertEquals(initial, draft.settings.value)
                    val persistedSettings = persisted.current().settings.value
                    // The real backend assigns its Session title and initial
                    // turn/window identities. Every caller-provided field must
                    // remain intact; late source admission must not alter them.
                    assertTrue(persistedSettings.turnId.isNotBlank())
                    assertTrue(persistedSettings.firstWindowId.isNotBlank())
                    assertEquals(persistedSettings.firstWindowId, persistedSettings.windowId)
                    val initialized = initial.copy(
                        threadName = "Session ${persisted.index}",
                        turnId = persistedSettings.turnId,
                        firstWindowId = persistedSettings.firstWindowId,
                        windowId = persistedSettings.windowId,
                    )
                    assertEquals(initialized, persistedSettings)
                    assertEquals(initialized, services.settings.get(
                        persisted.index, services.settings.getCacheNonce(persisted.index),
                        services.settings.getLatestIndex(persisted.index),
                    ))
                    withTimeout(5.seconds) { source.state.first { it == SessionSettingsDataState.Unavailable } }
                    assertFailsWith<IllegalStateException> { draft.edit { it.copy(model = OpenAiModelId("late")) } }
                } finally {
                    release.complete(Unit)
                    allocating.cancelAndJoin()
                    updating?.cancelAndJoin()
                    vm.close(); source.close(); draft.close()
                    vmOwner.cancelAndJoin()
                }
            }
        }
    }
    test("caller cancellation while actual draft source waits on materialize lock remains cancellation") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        frontend(decorate = { original ->
            object : RpcClient by original {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "createSession") {
                        entered.complete(Unit)
                        release.await()
                    }
                    return original.call(call)
                }
            }
        }) {
            val initial = KodexAgentSettings(OpenAiModelId("test-model"))
            val draft = RpcSessionDraft(initial, views, this)
            val source = RpcDraftSettingsSource(draft, this)
            val allocating = async(start = CoroutineStart.UNDISPATCHED) { draft.materialize() }
            var updating: Deferred<Boolean>? = null
            try {
                withTimeout(5.seconds) { entered.await() }
                val baseline = assertIs<SessionSettingsDataState.Available>(source.state.value).snapshot
                val caller = async(start = CoroutineStart.UNDISPATCHED) {
                    source.tryUpdateConfiguration(baseline.revision,
                        baseline.configuration.copy(model = OpenAiModelId("cancelled")))
                }
                updating = caller
                assertFalse(caller.isCompleted)
                caller.cancelAndJoin()
                assertFailsWith<CancellationException> { caller.await() }
                release.complete(Unit)
                val persisted = allocating.await()
                assertEquals(initial, draft.settings.value)
                val persistedSettings = persisted.current().settings.value
                assertTrue(persistedSettings.turnId.isNotBlank())
                assertTrue(persistedSettings.firstWindowId.isNotBlank())
                assertEquals(persistedSettings.firstWindowId, persistedSettings.windowId)
                assertEquals(initial.copy(
                    threadName = "Session ${persisted.index}",
                    turnId = persistedSettings.turnId,
                    firstWindowId = persistedSettings.firstWindowId,
                    windowId = persistedSettings.windowId,
                ), persistedSettings)
            } finally {
                release.complete(Unit)
                allocating.cancelAndJoin()
                updating?.cancelAndJoin()
                source.close(); draft.close()
            }
        }
    }
    test("closing real Session Settings cancels CAS waiting on the original draft mutex without late edit") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        frontend(decorate = { original ->
            object : RpcClient by original {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "createSession") {
                        entered.complete(Unit)
                        release.await()
                    }
                    return original.call(call)
                }
            }
        }) {
            val draft = RpcSessionDraft(KodexAgentSettings(OpenAiModelId("test-model")), views, this)
            val source = RpcDraftSettingsSource(draft, this)
            val vmOwner = Job(coroutineContext[Job])
            val scheduler = TestCoroutineScheduler()
            val vm = createSessionSettingsViewModel(
                SessionSettingsDependencies(source, MutableStateFlow(emptyList())),
                CoroutineScope(coroutineContext + vmOwner + UnconfinedTestDispatcher(scheduler)),
            )
            val allocating = async(start = CoroutineStart.UNDISPATCHED) { draft.materialize() }
            try {
                withTimeout(5.seconds) { entered.await() } // materialize holds draft's actual mutex.
                val baseline = assertIs<SessionSettingsState.Available>(vm.state.value).snapshot
                vm.updateModel(baseline.revision, OpenAiModelId("late-popup-write"))
                assertEquals(OpenAiModelId("test-model"), draft.settings.value.model)
                vm.close()
                vmOwner.cancelAndJoin()
                assertEquals(SessionSettingsDataState.Unavailable, source.state.value)
                release.complete(Unit)
                val persisted = allocating.await()
                assertEquals(OpenAiModelId("test-model"), persisted.current().settings.value.model)
                assertEquals(OpenAiModelId("test-model"), draft.settings.value.model)
            } finally {
                release.complete(Unit)
                allocating.cancelAndJoin()
                vm.close(); source.close(); draft.close()
                vmOwner.cancelAndJoin()
            }
        }
    }
}
