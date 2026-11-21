@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Real backend/default CAS and draft lifecycle, with no draft Session Settings source. */
val draftAndSettingsBoundaryTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("supplied draft parent cancellation joins Composer and settings observations") {
        frontend {
            val parent = Job(coroutineContext[Job])
            val draft = RpcSessionDraft(home, views, global, CoroutineScope(coroutineContext + parent))
            draft.composer.update("retained")
            parent.cancelAndJoin()
            assertEquals(ComposerLifecycle.Closed, draft.composer.state.value.lifecycle)
            assertTrue(parent.children.none())
            assertFailsWith<IllegalStateException> { draft.composer.update("late") }
            assertFailsWith<IllegalStateException> { draft.editDefaults({ it.model }, { it.copy(model = OpenAiModelId("late")) }) }
            assertTrue(services.global.getSessionCatalog(true).isEmpty())
            global.ensureActive()
        }
    }
    test("explicit draft close leaves borrowed global views sibling and backend turn alive") {
        val running = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(response = {
            running.complete(Unit)
            finish.await()
            emit(ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)))
        }) {
            val parent = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + parent)
            val first = RpcSessionDraft(home, views, global, scope)
            val sibling = RpcSessionDraft(home, views, global, scope)
            try {
                val index = services.global.createSession(home)
                val persisted = views.open(index)
                persisted.current().submit(listOf(ContentItem.InputText("backend turn")))
                try {
                    running.await()
                    first.close()
                    assertTrue(parent.isActive)
                    assertEquals(ComposerLifecycle.Closed, first.composer.state.value.lifecycle)
                    sibling.composer.update("still editable")
                    sibling.updateWorkingDirectory(SystemTemporaryDirectory)
                    sibling.editDefaults({ it.serviceTier }, { it.copy(serviceTier = ServiceTier.Fast) })
                    assertTrue(services.runtime.getRunningTurn(persisted.agentId))
                    global.ensureActive()
                    finish.complete(Unit)
                    services.runtime.getRunningTurnFlow(persisted.agentId).first { !it }
                    assertEquals(1, services.global.getSessionCatalog(true).size)
                } finally { finish.complete(Unit) }
            } finally { finish.complete(Unit); first.close(); sibling.close(); parent.cancelAndJoin() }
        }
    }
    test("no selected Session has a stateless unavailable source instead of a draft editor") {
        frontend {
            val source = UnavailableSessionSettingsDataSource
            val vm = createSessionSettingsViewModel(SessionSettingsDependencies(source, global.models), this)
            try {
                assertEquals(SessionSettingsDataState.Unavailable, source.state.value)
                assertEquals(SessionSettingsState.Unavailable, vm.state.value)
                assertFalse(source.tryRenameSession(0, "not a draft name"))
                assertFalse(source.tryUpdateConfiguration(0, SessionSettingsConfiguration(
                    OpenAiModelId("unused"), home, ReasoningEffort.Medium, ServiceTier.Default, RequestUserInputMode.AskUser,
                )))
                vm.close()
                assertEquals(SessionSettingsDataState.Unavailable, source.state.value)
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
            } finally { vm.close() }
        }
    }
    for (sameField in listOf(false, true)) {
        test("draft defaults CAS preserves unrelated fields and rejects changed tuple target=$sameField") {
            frontend {
                var attempts = 0
                val rpc = object : GlobalRpc by services.global {
                    override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean {
                        attempts++
                        if (attempts == 1) {
                            val competing = expect.copy(
                                newSession = expect.newSession.copy(
                                    model = if (sameField) OpenAiModelId("competing") else expect.newSession.model,
                                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                                ),
                                sessionTitle = expect.sessionTitle.copy(model = OpenAiModelId("unrelated-title")),
                            )
                            assertTrue(services.global.compareAndSetSettings(expect, competing))
                        }
                        return services.global.compareAndSetSettings(expect, update)
                    }
                }
                val observed = RpcGlobalSettings.open(rpc, this, 80)
                val draft = RpcSessionDraft(home, views, observed, this)
                try {
                    suspend fun edit() = draft.editDefaults(
                        { Triple(it.model, it.reasoningEffort, it.serviceTier) },
                        { it.copy(model = OpenAiModelId("requested"), reasoningEffort = ReasoningEffort.Max, serviceTier = ServiceTier.Fast) },
                    )
                    if (sameField) assertFailsWith<IllegalStateException> { edit() } else edit()
                    val result = services.global.getSettings()
                    assertEquals(OpenAiModelId(if (sameField) "competing" else "requested"), result.newSession.model)
                    assertEquals(if (sameField) ReasoningEffort.Medium else ReasoningEffort.Max, result.newSession.reasoningEffort)
                    assertEquals(if (sameField) ServiceTier.Default else ServiceTier.Fast, result.newSession.serviceTier)
                    assertEquals(RequestUserInputMode.NoQuestion, result.newSession.requestUserInputMode)
                    assertEquals(OpenAiModelId("unrelated-title"), result.sessionTitle.model)
                    assertEquals(if (sameField) 1 else 2, attempts)
                    assertTrue(services.global.getSessionCatalog(true).isEmpty())
                } finally { draft.close(); observed.close(); observed.join() }
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
                    override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean {
                        writes++
                        entered.complete(Unit)
                        cancelCommand.await()
                        throw CancellationException("one command cancelled")
                    }
                }
                val observed = RpcGlobalSettings.open(rpc, this, 80)
                val page = if (defaults) null else RpcGlobalEditor(observed, rpc, this, home.toString())
                val defaultPage = if (defaults) RpcNewSessionSettings(observed, this) else null
                try {
                    if (defaults) defaultPage!!.updateModel(defaultPage.state.value.revision, OpenAiModelId("cancelled"))
                    else page!!.sessionTitleSettings.setEnabled(true)
                    entered.await()
                    cancelCommand.complete(Unit)
                    yield(); yield()
                    assertTrue(coroutineContext[Job]!!.isActive)
                    assertFalse(observed.operationFailure.value)
                    if (defaults) defaultPage!!.updateModel(defaultPage.state.value.revision, OpenAiModelId("must-reject"))
                    else page!!.sessionTitleSettings.setEnabled(true)
                    observed.operationFailure.first { it }
                    assertEquals(1, writes, "Dead consumer must reject, not falsely admit a later write.")
                    assertNotEquals(OpenAiModelId("must-reject"), services.global.getSettings().newSession.model)
                } finally {
                    cancelCommand.complete(Unit)
                    page?.close(); defaultPage?.close(); observed.close(); observed.join()
                }
            }
        }
    }
    test("zero length and stale revisions never create or open a Session") {
        frontend {
            val draft = RpcSessionDraft(home, views, global, this)
            var opens = 0
            try {
                assertNull(draft.submit(draft.composer.state.value.revision) { opens++; views.open(it) })
                val old = draft.composer.update("old")
                draft.composer.update("new")
                assertNull(draft.submit(old) { opens++; views.open(it) })
                assertEquals("new", draft.composer.state.value.text)
                assertEquals(0, opens)
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
            } finally { draft.close() }
        }
    }
    test("cwd-only creation uses backend defaults even while the frontend defaults subscription is paused") {
        frontend {
            val release = CompletableDeferred<Unit>()
            val rpc = object : GlobalRpc by services.global {
                override fun getSettingsFlow(): Flow<BackendSettings> = flow {
                    release.await()
                    emitAll(services.global.getSettingsFlow())
                }
            }
            val observed = RpcGlobalSettings.open(rpc, this, 80)
            val draft = RpcSessionDraft(home, views, observed, this)
            try {
                val old = services.global.getSettings()
                val actual = old.copy(newSession = old.newSession.copy(
                    model = OpenAiModelId("backend-current"),
                    reasoningEffort = ReasoningEffort.Max,
                    serviceTier = ServiceTier.Fast,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                ))
                assertTrue(services.global.compareAndSetSettings(old, actual))
                assertEquals(OpenAiModelId("test-model"), observed.settings.value.newSession.model)
                assertEquals(OpenAiModelId("test-model"), draft.settings.value.model)
                val cwd = SystemTemporaryDirectory
                draft.updateWorkingDirectory(cwd)
                val view = assertNotNull(withTimeout(5.seconds) {
                    draft.submit(draft.composer.update("no defaults barrier"), views::open)
                })
                val created = view.current().settings.value
                assertEquals(OpenAiModelId("backend-current"), created.model)
                assertEquals(ReasoningEffort.Max, created.reasoning.effort)
                assertEquals(ServiceTier.Fast, created.serviceTier)
                assertEquals(RequestUserInputMode.NoQuestion, created.requestUserInputMode)
                assertEquals(cwd, created.cwd)
                assertFalse(release.isCompleted)
                release.complete(Unit)
                draft.settings.first { it.model == OpenAiModelId("backend-current") && it.cwd == home }
            } finally { release.complete(Unit); draft.close(); observed.close(); observed.join() }
        }
    }
    for ((label, text) in listOf("spaces" to "   ", "newlines" to "\n\n", "padded" to " \n first message \t\n")) {
        test("nonzero $label text is submitted byte-for-byte") {
            frontend {
                val draft = RpcSessionDraft(home, views, global, this)
                try {
                    val revision = draft.composer.update(text)
                    val view = assertNotNull(draft.submit(revision, views::open))
                    val message = readFirstUserMessage(services, view.agentId)
                    assertEquals(listOf(ContentItem.InputText(text)), message.content)
                    assertEquals("", draft.composer.state.value.text)
                } finally { draft.close() }
            }
        }
    }
    for (boundary in listOf("createSession", "submit")) {
        test("in-flight $boundary duplicates do not queue and late completion preserves newer text and ABA directory edit") {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var creations = 0
            var opened = false
            frontend(decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "createSession") creations++
                        if (call.callableName == "submit") assertTrue(opened)
                        if (call.callableName == boundary && creations == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return original.call(call)
                    }
                }
            }) {
                val draft = RpcSessionDraft(home, views, global, this)
                val capturedCwd = SystemTemporaryDirectory
                draft.updateWorkingDirectory(capturedCwd)
                val revision = draft.composer.update("captured")
                val submitting = async {
                    draft.submit(revision) { index -> views.open(index).also { opened = true } }
                }
                try {
                    entered.await()
                    assertEquals("captured", draft.composer.state.value.text)
                    draft.settings.first { it.cwd == capturedCwd }
                    assertNull(withTimeout(2.seconds) { draft.submit(revision, views::open) })
                    val later = draft.composer.update("later")
                    draft.updateWorkingDirectory(home)
                    draft.updateWorkingDirectory(capturedCwd)
                    assertNull(withTimeout(2.seconds) { draft.submit(later, views::open) })
                    assertEquals(1, creations)
                    release.complete(Unit)
                    val first = assertNotNull(submitting.await())
                    assertEquals(capturedCwd, first.current().settings.value.cwd)
                    assertEquals("later", draft.composer.state.value.text)
                    assertEquals(later, draft.composer.state.value.revision)
                    yield()
                    assertEquals(capturedCwd, draft.settings.value.cwd)
                    assertNull(draft.submit(revision, views::open))
                    val next = assertNotNull(draft.submit(later, views::open))
                    assertNotEquals(first.sessionIndex, next.sessionIndex)
                    assertEquals(2, creations)
                    assertEquals("", draft.composer.state.value.text)
                    draft.settings.first { it.cwd == home }
                } finally { release.complete(Unit); submitting.cancelAndJoin(); draft.close() }
            }
        }
    }
    test("normal success resets only after submit admission and does not wait for model completion") {
        val running = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(response = {
            running.complete(Unit)
            finish.await()
            emit(ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)))
        }) {
            val draft = RpcSessionDraft(home, views, global, this)
            try {
                draft.updateWorkingDirectory(SystemTemporaryDirectory)
                val revision = draft.composer.update("accepted")
                val view = assertNotNull(withTimeout(5.seconds) { draft.submit(revision, views::open) })
                running.await()
                assertTrue(services.runtime.getRunningTurn(view.agentId))
                assertFalse(finish.isCompleted)
                assertEquals("", draft.composer.state.value.text)
                draft.settings.first { it.cwd == home }
            } finally { finish.complete(Unit); draft.close() }
        }
    }
    test("renderer wait cancellation does not cancel submission invoked by a surviving frontend command owner") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        frontend(decorate = { original ->
            object : RpcClient by original {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "submit") {
                        entered.complete(Unit)
                        release.await()
                    }
                    return original.call(call)
                }
            }
        }) {
            val commandOwner = Job(coroutineContext[Job])
            val frontendScope = CoroutineScope(coroutineContext + commandOwner)
            val draft = RpcSessionDraft(home, views, global, frontendScope)
            val revision = draft.composer.update("surviving command")
            val accepted = frontendScope.async { draft.submit(revision, views::open) }
            val rendererWait = launch { accepted.await() }
            try {
                entered.await()
                rendererWait.cancelAndJoin()
                assertTrue(accepted.isActive)
                assertEquals("surviving command", draft.composer.state.value.text)
                release.complete(Unit)
                assertNotNull(accepted.await())
                assertEquals("", draft.composer.state.value.text)
                assertEquals(1, services.global.getSessionCatalog(true).size)
            } finally {
                release.complete(Unit)
                rendererWait.cancelAndJoin()
                accepted.cancelAndJoin()
                draft.close()
                commandOwner.cancelAndJoin()
            }
        }
    }
    for (phase in listOf("createSession", "open", "submit")) {
        test("$phase failure propagates without reset replay deletion or extra submit") {
            var creates = 0
            var submits = 0
            frontend(decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        when (call.callableName) {
                            "createSession" -> creates++
                            "submit" -> submits++
                        }
                        val result = original.call<T>(call)
                        if (call.callableName == phase) error("lost $phase reply")
                        return result
                    }
                }
            }) {
                val draft = RpcSessionDraft(home, views, global, this)
                try {
                    val cwd = SystemTemporaryDirectory
                    draft.updateWorkingDirectory(cwd)
                    val revision = draft.composer.update("keep exact input")
                    assertFailsWith<IllegalStateException> {
                        draft.submit(revision) { index ->
                            if (phase == "open") error("open failed")
                            views.open(index)
                        }
                    }
                    assertEquals("keep exact input", draft.composer.state.value.text)
                    assertEquals(revision, draft.composer.state.value.revision)
                    draft.settings.first { it.cwd == cwd }
                    assertEquals(1, creates)
                    assertEquals(if (phase == "submit") 1 else 0, submits)
                    assertEquals(1, services.global.getSessionCatalog(true).size)
                    draft.composer.update("still editable")
                    draft.updateWorkingDirectory(home)
                } finally { draft.close() }
            }
        }
    }
    test("command owner cancellation during submit propagates without reset") {
        val entered = CompletableDeferred<Unit>()
        frontend(decorate = { original ->
            object : RpcClient by original {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "submit") {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    return original.call(call)
                }
            }
        }) {
            val draft = RpcSessionDraft(home, views, global, this)
            val revision = draft.composer.update("retained")
            val command = async { draft.submit(revision, views::open) }
            try {
                entered.await()
                command.cancelAndJoin()
                assertFailsWith<CancellationException> { command.await() }
                assertEquals("retained", draft.composer.state.value.text)
                assertEquals(1, services.global.getSessionCatalog(true).size)
            } finally { command.cancelAndJoin(); draft.close() }
        }
    }
}

private suspend fun readFirstUserMessage(services: RpcServices, agentId: Int): StableUserMessage =
    withTimeout(5.seconds) {
        while (true) {
            try {
                val nonce = services.runtime.getCacheNonce(agentId)
                val latest = services.runtime.getLatestIndex(agentId)
                val found = if (latest >= 0) {
                    services.runtime.getFirstCommitsIn(
                        agentId,
                        nonce,
                        0,
                        latest,
                        setOf(AgentCommitType.UserMessage),
                        limit = 1,
                    ).firstOrNull()
                } else {
                    null
                }
                if (found != null) {
                    return@withTimeout assertIs<StableUserMessage>(found.commit)
                }
                delay(10)
            } catch (_: CacheNonceMismatch) {
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }
