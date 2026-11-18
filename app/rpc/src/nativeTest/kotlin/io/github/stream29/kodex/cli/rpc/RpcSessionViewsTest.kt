package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.client.update
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.utils.rpcexception.ShellSessionNotRegistered
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import io.ktor.http.URLBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val rpcSessionViewsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (closeWhileDisconnected in listOf(false, true)) {
        test("transport-lost read binding ${if (closeWhileDisconnected) "closes without resurrection" else "rebuilds without write replay"}") {
            val fail = CompletableDeferred<Unit>()
            val available = MutableStateFlow(true)
            var subscribed = false
            var submits = 0
            frontend(connectionAvailable = { available.value }, decorate = { delegate ->
                object : RpcClient by delegate {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "submit") submits++
                        return delegate.call(call)
                    }
                    override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
                        val upstream = delegate.callServerStreaming<T>(call)
                        if (call.callableName != "getStateFlow" || subscribed) return upstream
                        subscribed = true
                        return flow {
                            coroutineScope {
                                val breaker = launch {
                                    fail.await()
                                    throw BackendConnectionLost("exact fixture transport closed")
                                }
                                try { upstream.collect { emit(it) } } finally { breaker.cancel() }
                            }
                        }
                    }
                }
            }) {
                val view = views.open(services.global.createSession(home))
                val old = view.current()
                val oldAgent = requireNotNull(view.rootAgent.value)
                old.submit(listOf(ContentItem.InputText("accepted once")))
                old.running.first { !it }
                available.value = false
                fail.complete(Unit)
                view.rootAgent.first { it == null }
                view.status.first { it == SessionViewStatus.Loading }
                assertEquals(1, submits)
                if (closeWhileDisconnected) {
                    views.release(view)
                    available.value = true
                    view.join()
                    assertEquals(SessionViewStatus.Closed, view.status.value)
                    assertNull(view.rootAgent.value)
                } else {
                    available.value = true
                    val rebound = view.binding.filterNotNull().first { it !== old }
                    view.status.first { it == SessionViewStatus.Ready }
                    assertNotSame(oldAgent, view.rootAgent.value)
                    assertEquals(ComposerLifecycle.Closed, oldAgent.composer.state.value.lifecycle)
                    assertSame(view, views.open(view.sessionIndex))
                    assertEquals(AgentStateValue.AssistantMessage, rebound.state.value)
                }
                assertEquals(1, submits)
            }
        }
    }
    test("a live-transport reactivation failure affects only its captured Session owner") {
        lateinit var client: RpcClient
        frontend(decorate = { delegate -> client = delegate; delegate }) {
            val bad = views.open(services.global.createSession(home))
            val healthy = views.open(services.global.createSession(home))
            val retainedAgent = healthy.rootAgent.value
            var activations = 0
            val replacement = RpcServices(object : RpcClient by client {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive" && ++activations == 1) {
                        throw Throwable("damaged Session fixture")
                    }
                    return client.call(call)
                }
            })
            views.reactivate(replacement) { true }
            val failed = bad.status.filterIsInstance<SessionViewStatus.Failed>().first()
            assertEquals("damaged Session fixture", failed.cause.message)
            assertEquals(2, activations)
            assertEquals(SessionViewStatus.Ready, healthy.status.value)
            assertSame(retainedAgent, healthy.rootAgent.value)
        }
    }
    test("a lost replacement transport is not recorded as a Session business failure") {
        lateinit var client: RpcClient
        frontend(decorate = { delegate -> client = delegate; delegate }) {
            val view = views.open(services.global.createSession(home))
            val retained = view.rootAgent.value
            val replacement = RpcServices(object : RpcClient by client {
                override suspend fun <T> call(call: RpcCall): T = error("fixture socket ended")
            })
            assertFailsWith<IllegalStateException> { views.reactivate(replacement) { false } }
            assertEquals(SessionViewStatus.Ready, view.status.value)
            assertSame(retained, view.rootAgent.value)
        }
    }
    test("blank drafts and catalog construction allocate nothing") {
        frontend {
            val draft = RpcSessionDraft(home, views, global, this)
            draft.composer.update("draft")
            draft.updateWorkingDirectory(Path(home, "local"))
            val catalog = DefaultSessionCatalogViewModel(this, RpcSessionCatalogDependencies(services.global))
            try {
                assertEquals(SessionCatalogState.Unloaded, catalog.state.value)
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                catalog.refresh()
                assertTrue(catalog.state.value.sessions.isEmpty())
            } finally { catalog.close(); draft.close() }
        }
    }

    test("concurrent opens share a view and local release leaves backend and connection alive") {
        frontend {
            val index = services.global.createSession(home)
            val first = async { views.open(index) }
            val second = async { views.open(index) }
            val view = first.await()
            assertSame(view, second.await())
            assertEquals(SessionViewStatus.Ready, view.status.value)
            val originalNonce = view.current().storage.cacheNonce.value
            views.release(view)
            assertEquals(SessionViewStatus.Closed, view.status.value)
            assertTrue(services.global.getSessionCatalog(false).single().isActive)
            val reopened = views.open(index)
            assertNotSame(view, reopened)
            assertEquals(originalNonce, reopened.current().storage.cacheNonce.value)
            assertTrue(services.global.getSettings().mcpServers.isEmpty())
        }
    }

    test("missing open reports SessionNotFound and never creates a replacement") {
        frontend {
            assertFailsWith<SessionNotFound> { views.open(999) }
            assertTrue(services.global.getSessionCatalog(true).isEmpty())
        }
    }

    test("a first activation failure is evicted and a later open can succeed") {
        var attempts = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive" && ++attempts == 1) {
                        error("initial activation failed")
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val index = services.global.createSession(home)
            assertFailsWith<IllegalStateException> { views.open(index) }
            val view = views.open(index)
            assertEquals(SessionViewStatus.Ready, view.status.value)
            assertEquals(2, attempts)
            views.release(view)
        }
    }

    test("a terminal initial SessionNotFound does not poison manual retry") {
        var attempts = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive" && ++attempts == 1) {
                        throw SessionNotFound()
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val index = services.global.createSession(home)
            assertFailsWith<SessionNotFound> { views.open(index) }
            val view = views.open(index)
            assertEquals(SessionViewStatus.Ready, view.status.value)
            assertEquals(2, attempts)
            views.release(view)
        }
    }

    test("concurrent callers of a failed initial view do not poison the next open") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var attempts = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive" && ++attempts == 1) {
                        entered.complete(Unit)
                        proceed.await()
                        error("initial activation failed")
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val index = services.global.createSession(home)
            val first = async { runCatching { views.open(index) } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { views.open(index) } }
            proceed.complete(Unit)
            assertIs<IllegalStateException>(first.await().exceptionOrNull())
            assertIs<IllegalStateException>(second.await().exceptionOrNull())
            val view = views.open(index)
            assertEquals(SessionViewStatus.Ready, view.status.value)
            assertEquals(2, attempts)
            views.release(view)
        }
    }

    test("cancelled first waiter does not evict a still-initializing shared view") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var attempts = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive") {
                        attempts++
                        entered.complete(Unit)
                        proceed.await()
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val index = services.global.createSession(home)
            val waiter = launch { views.open(index) }
            entered.await()
            waiter.cancelAndJoin()
            proceed.complete(Unit)
            val view = views.open(index)
            assertEquals(SessionViewStatus.Ready, view.status.value)
            assertEquals(1, attempts)
            views.release(view)
        }
    }

    test("delete invalidates all old bindings and terminates automatic recovery") {
        frontend {
            val index = services.global.createSession(home)
            val view = views.open(index)
            val binding = view.current()
            services.global.deleteSession(index)
            view.status.first { it == SessionViewStatus.Missing }
            assertNull(view.binding.value)
            assertFailsWith<CancellationException> { binding.storage.getSettingsAt(0) }
            assertTrue(services.global.getSessionCatalog(true).isEmpty())
            views.release(view)
        }
    }

    test("settings and token projection observe real values and successful CAS never fabricates a reply value") {
        frontend {
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            val initial = binding.settings.value
            assertEquals(0, binding.latestIndex.value)
            assertEquals(0L, binding.tokenCount.value?.totalTokens)
            assertTrue(binding.settings.compareAndSet(initial, initial.copy(instructions = "edited")))
            binding.settings.first { it.instructions == "edited" }
            assertFalse(binding.settings.compareAndSet(initial, initial.copy(threadName = "stale")))
            assertEquals(
                "Session ${view.sessionIndex}",
                checkNotNull(binding.storage.getSettingsAt(binding.latestIndex.value)).threadName,
            )
        }
    }

    test("submit and rollback refresh the original history using the backend nonce") {
        frontend {
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            val agent = requireNotNull(view.rootAgent.value)
            binding.submit(listOf(ContentItem.InputText("history")))
            binding.state.first { it == AgentStateValue.AssistantMessage }
            binding.running.first { !it }
            val nonce = binding.storage.cacheNonce.value
            val committed = services.runtime.getFirstCommitsIn(
                view.sessionIndex,
                nonce,
                0,
                binding.latestIndex.value,
                setOf(io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType.UserMessage),
            ).single().index
            val old = agent.historyIndex.window.first { it.generation == nonce && committed in it.indexes }
            assertTrue(assertNotNull(old.entryAtOrNull(committed)).summary.contains("history"))
            agent.history.historyItems.first { it.size > 0 }
            val latestBeforeCheckout = binding.latestIndex.value
            assertTrue(agent.historyIndex.checkOut(old.generation, committed))
            assertEquals(latestBeforeCheckout, binding.latestIndex.value) // Check out did not revert history.
            binding.revertHistory(committed, old.generation)
            val changed = agent.historyIndex.window.first { it.generation != old.generation }
            assertEquals(binding.storage.cacheNonce.value, changed.generation)
            assertFalse(agent.historyIndex.contains(old.generation, committed))
            assertFalse(agent.historyIndex.checkOut(old.generation, committed))
            assertFailsWith<CacheNonceMismatch> { binding.forkHistory(committed, old.generation) }
            binding.settings.first { it.threadName == "Session ${view.sessionIndex}" }
        }
    }

    test("catalog dates use the snapshot while mutations are backend commands") {
        frontend {
            val index = services.global.createSession(home)
            val catalog = DefaultSessionCatalogViewModel(this, RpcSessionCatalogDependencies(services.global))
            try {
                catalog.refresh()
                val entry = catalog.state.value.sessions.single()
                assertNotNull(entry.createdAt)
                assertNotNull(entry.updatedAt)
                catalog.archive(index)
                assertTrue(catalog.state.value.sessions.isEmpty())
                catalog.setShowArchived(true)
                assertTrue(catalog.state.value.sessions.single().archived)
                catalog.unarchive(index)
                val fork = catalog.fork(index)
                assertNotEquals(index, fork)
                assertTrue(
                    requireNotNull(
                        catalog.requestDelete(catalog.state.value.sessions.single { it.sessionIndex == fork }),
                    ).viewModel.delete(),
                )
                assertEquals(index, catalog.state.value.sessions.single().sessionIndex)
            } finally { catalog.close() }
        }
    }

    test("runtime component binds exact Agent and atomically preserves unrelated fields") {
        frontend {
            val view = views.open(services.global.createSession(home))
            val initial = view.current().settings
            initial.update { it.copy(threadName = "fixed", instructions = "keep") }
            initial.first { it.threadName == "fixed" && it.instructions == "keep" }
            val other = views.open(services.global.createSession(home))
            other.current().settings.update { it.copy(threadName = "other") }
            other.current().settings.first { it.threadName == "other" }
            val agent = requireNotNull(view.rootAgent.value)
            try {
                val child = agent.runtimeConfiguration
                val before = agent.settings.value
                assertSame(child, agent.runtimeConfiguration)
                child.updateModelConfiguration(OpenAiModelId("edited"), ReasoningEffort.Max, ServiceTier.Fast)
                agent.settings.first { it.model == OpenAiModelId("edited") }
                assertEquals(ReasoningEffort.Max, agent.settings.value.reasoning.effort)
                assertEquals(ServiceTier.Fast, agent.settings.value.serviceTier)
                assertEquals(before.threadName, agent.settings.value.threadName)
                assertEquals(before.instructions, agent.settings.value.instructions)
                assertEquals(OpenAiModelId("test-model"), other.current().settings.value.model)
                child.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
                agent.settings.first { it.requestUserInputMode == RequestUserInputMode.NoQuestion }
                agent.close()
                assertTrue(child.state.value.closed)
                child.updateModelConfiguration(OpenAiModelId("late"), ReasoningEffort.Low, ServiceTier.Default)
                assertEquals(OpenAiModelId("edited"), view.current().settings.value.model)
                assertEquals(OpenAiModelId("test-model"), other.current().settings.value.model)
            } finally { agent.close() }
        }
    }

    test("bound Composer stale and closed submissions never issue an RPC command") {
        var submits = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "submit") submits++
                    return delegate.call(call)
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val composer = requireNotNull(view.rootAgent.value).composer
            val original = composer.update("original")
            val current = composer.update("current")
            composer.submit(original)
            assertEquals("current", composer.state.value.text)
            assertEquals(current, composer.state.value.revision)
            views.release(view)
            assertEquals(ComposerLifecycle.Closed, composer.state.value.lifecycle)
            composer.submit(current)
            assertEquals(SessionViewStatus.Closed, view.status.value)
            assertEquals(0, submits)
        }
    }

    test("cancelled caller of bound Composer submit restores editing without Stop or Agent failure") {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var submits = 0
        var stops = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    when (call.callableName) {
                        "submit" -> {
                            submits++
                            entered.complete(Unit)
                            try { awaitCancellation() } finally { cancelled.complete(Unit) }
                        }
                        "cancelRunningTurn" -> stops++
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            val agent = requireNotNull(view.rootAgent.value)
            val revision = agent.composer.update("  retained cancellation draft  ", 4)
            val waiting = async { agent.composer.submit(revision) }
            entered.await()
            waiting.cancelAndJoin()
            cancelled.await()
            assertFailsWith<CancellationException> { waiting.await() }
            assertEquals("  retained cancellation draft  ", agent.composer.state.value.text)
            assertEquals(4, agent.composer.state.value.cursorOffset)
            assertEquals(revision, agent.composer.state.value.revision)
            assertIs<ComposerSubmissionState.Editing>(agent.composer.state.value.submission)
            assertEquals(SessionViewStatus.Ready, view.status.value)
            assertSame(binding, view.current())
            assertEquals(1, submits)
            assertEquals(0, stops)
        }
    }

    test("bound Composer lost submit reply reports once without rollback retry or draft loss") {
        var submits = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    val result = delegate.call<T>(call)
                    if (call.callableName == "submit") {
                        submits++
                        error("accepted submit reply lost")
                    }
                    return result
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val agent = requireNotNull(view.rootAgent.value)
            try {
                val revision = agent.composer.update("  persisted but retained  ", 6)
                agent.composer.submit(revision)
                assertEquals(
                    "accepted submit reply lost",
                    assertIs<ComposerSubmissionState.Failed>(agent.composer.state.value.submission).failure.message,
                )
                assertEquals("accepted submit reply lost", failures.first { it.size == 1 }.single().message)
                assertEquals("  persisted but retained  ", agent.composer.state.value.text)
                assertEquals(6, agent.composer.state.value.cursorOffset)
                assertEquals(revision + 1, agent.composer.state.value.revision)
                services.runtime.getRunningTurnFlow(view.sessionIndex).first { !it }
                assertEquals(AgentStateValue.AssistantMessage, services.runtime.getState(view.sessionIndex))
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertEquals(1, submits)
            } finally { agent.close() }
        }
    }

    test("root Stop uses the caught ordinary-failure boundary") {
        val stopFailure = IllegalStateException("ordinary stop failure")
        var stops = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "cancelRunningTurn") { stops++; throw stopFailure }
                    return delegate.call(call)
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            val agent = requireNotNull(view.rootAgent.value)
            try {
                agent.cancel()
                assertEquals("ordinary stop failure", failures.first { it.size == 1 }.single().message)
                assertEquals(1, stops)
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertSame(binding, view.current())
            } finally { agent.close() }
        }
    }

    test("composer queues while running and closing the tab does not stop the accepted turn") {
        val entered = CompletableDeferred<Unit>()
        val preempted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        frontend(response = {
            val request = ++requests
            entered.complete(Unit)
            try {
                finish.await()
                answer()
            } finally {
                if (request == 1 && !currentCoroutineContext().isActive) {
                    withContext(NonCancellable) {
                        preempted.complete(Unit)
                        finish.await()
                    }
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            binding.submit(listOf(ContentItem.InputText("start")))
            entered.await()
            binding.running.first { it }
            val agent = requireNotNull(view.rootAgent.value)
            agent.composer.state.first { it.running }
            val revision = agent.composer.update("steer")
            agent.composer.submit(revision)
            assertEquals("", agent.composer.state.value.text)
            preempted.await()
            binding.pendingSteer.first { it.isNotEmpty() }
            views.release(view)
            assertTrue(services.runtime.getRunningTurn(view.sessionIndex))
            finish.complete(Unit)
            services.runtime.getRunningTurnFlow(view.sessionIndex).first { !it }
            assertEquals(AgentStateValue.AssistantMessage, services.runtime.getState(view.sessionIndex))
            assertEquals(2, requests)
        }
    }

    test("reusable draft rejects a consumed revision and creates a second Session for a new revision") {
        frontend {
            val draft = RpcSessionDraft(home, views, global, this)
            try {
                val revision = draft.composer.update("first")
                val view = assertNotNull(draft.submit(revision, views::open))
                assertEquals("", draft.composer.state.value.text)
                view.current().state.first { it == AgentStateValue.AssistantMessage }
                assertNull(draft.submit(revision, views::open))
                val next = draft.composer.update("second")
                assertNull(draft.submit(revision, views::open))
                assertEquals("second", draft.composer.state.value.text)
                val second = assertNotNull(draft.submit(next, views::open))
                assertNotEquals(view.sessionIndex, second.sessionIndex)
                assertEquals(2, services.global.getSessionCatalog(true).size)
            } finally { draft.close() }
        }
    }

    test("current output has shared replay and stops observing when the view closes") {
        val emitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(response = {
            emit(ResponsesStreamEvent.OutputItemAdded(0, ResponseItem.Message(
                id = ResponseItemId("stream"), role = MessageRole.Assistant, content = emptyList(),
            )))
            emit(ResponsesStreamEvent.OutputTextDelta("stream", 0, 0, "part"))
            emitted.complete(Unit)
            finish.await()
            answer()
        }) {
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            binding.submit(listOf(ContentItem.InputText("stream please")))
            emitted.await()
            val output = binding.displayState.filterIsInstance<KodexAgentStateValue.RequestResponse.Message>().first()
            val first = async { output.events.first { it is ResponsesStreamEvent.OutputTextDelta } }
            val second = async { output.events.first { it is ResponsesStreamEvent.OutputTextDelta } }
            assertEquals(first.await(), second.await())
            views.release(view)
            finish.complete(Unit)
            services.runtime.getRunningTurnFlow(view.sessionIndex).first { !it }
            assertEquals(SessionViewStatus.Closed, view.status.value)
        }
    }

    test("an inactive upstream rebuilds all bindings without replaying an accepted submit") {
        val fail = CompletableDeferred<Unit>()
        var subscribed = false
        var keepalives = 0
        var submits = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive") keepalives++
                    if (call.callableName == "submit") submits++
                    return delegate.call(call)
                }
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
                    val upstream = delegate.callServerStreaming<T>(call)
                    if (call.callableName != "getStateFlow" || subscribed) return upstream
                    subscribed = true
                    return flow {
                        coroutineScope {
                            val breaker = launch { fail.await(); throw SessionNotActive() }
                            try { upstream.collect { emit(it) } } finally { breaker.cancel() }
                        }
                    }
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val old = view.current()
            val oldAgent = requireNotNull(view.rootAgent.value)
            val oldComposer = oldAgent.composer
            old.submit(listOf(ContentItem.InputText("once")))
            old.state.first { it == AgentStateValue.AssistantMessage }
            old.running.first { !it }
            fail.complete(Unit)
            val recovered = view.binding.filterNotNull().first { it !== old }
            view.status.first { it == SessionViewStatus.Ready }
            val recoveredAgent = requireNotNull(view.rootAgent.value)
            assertNotSame(oldAgent, recoveredAgent)
            assertNotSame(oldComposer, recoveredAgent.composer)
            assertSame(recovered.settings, recoveredAgent.settings)
            oldAgent.lifecycle.first { it == io.github.stream29.kodex.app.agent.contract.AgentLifecycleState.Closed }
            assertEquals(ComposerLifecycle.Closed, oldComposer.state.value.lifecycle)
            oldComposer.submit(oldComposer.state.value.revision)
            assertFailsWith<CancellationException> { oldAgent.renameThread("expired binding") }
            assertSame(recoveredAgent, view.rootAgent.value)
            assertEquals(2, keepalives)
            assertEquals(1, submits)
            assertEquals(AgentStateValue.AssistantMessage, recovered.state.value)
            assertFailsWith<CancellationException> { old.storage.getSettingsAt(0) }
            assertSame(view, views.open(view.sessionIndex))
        }
    }

    test("close during reactivation cancels the frontend wait and never resurrects the view") {
        val fail = CompletableDeferred<Unit>()
        val reactivating = CompletableDeferred<Unit>()
        var subscribed = false
        var keepalives = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive" && ++keepalives == 2) {
                        reactivating.complete(Unit)
                        awaitCancellation()
                    }
                    return delegate.call(call)
                }
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
                    val upstream = delegate.callServerStreaming<T>(call)
                    if (call.callableName != "getStateFlow" || subscribed) return upstream
                    subscribed = true
                    return flow {
                        coroutineScope {
                            val breaker = launch { fail.await(); throw SessionNotActive() }
                            try { upstream.collect { emit(it) } } finally { breaker.cancel() }
                        }
                    }
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            fail.complete(Unit)
            reactivating.await()
            views.release(view)
            assertEquals(SessionViewStatus.Closed, view.status.value)
            assertNull(view.binding.value)
            assertNull(view.rootAgent.value)
            assertEquals(2, keepalives)
            assertTrue(services.global.getSessionCatalog(true).single().isActive)
        }
    }

    test("an unknown observer failure is visible and does not start an automatic recovery loop") {
        val fail = CompletableDeferred<Unit>()
        var keepalives = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive") keepalives++
                    return delegate.call(call)
                }
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> {
                    val upstream = delegate.callServerStreaming<T>(call)
                    if (call.callableName != "getStateFlow") return upstream
                    return flow {
                        coroutineScope {
                            val breaker = launch { fail.await(); error("observer failure") }
                            try { upstream.collect { emit(it) } } finally { breaker.cancel() }
                        }
                    }
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            fail.complete(Unit)
            val failure = view.status.filterIsInstance<SessionViewStatus.Failed>().first()
            assertEquals("observer failure", failure.cause.message)
            assertNull(view.binding.value)
            assertEquals(1, keepalives)
            assertFailsWith<IllegalStateException> { view.current() }
        }
    }

    test("a lost submit reply keeps the created entity and draft without automatic retries") {
        var submits = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    val result = delegate.call<T>(call)
                    if (call.callableName == "submit") {
                        submits++
                        error("lost reply")
                    }
                    return result
                }
            }
        }) {
            val draft = RpcSessionDraft(home, views, global, this)
            try {
                val revision = draft.composer.update("keep me")
                assertFailsWith<IllegalStateException> { draft.submit(revision, views::open) }
                val index = services.global.getSessionCatalog(true).single().sessionIndex
                assertEquals("keep me", draft.composer.state.value.text)
                services.runtime.getRunningTurnFlow(index).first { !it }
                assertEquals(AgentStateValue.AssistantMessage, services.runtime.getState(index))
                assertEquals(1, submits)
            } finally { draft.close() }
        }
    }

    test("an idle background view renews without UI collectors and stops renewing on release") {
        val renewed = CompletableDeferred<Unit>()
        var keepalives = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    val result = delegate.call<T>(call)
                    if (call.callableName == "keepSessionAlive" && ++keepalives == 2) renewed.complete(Unit)
                    return result
                }
            }
        }) {
            val view = views.open(services.global.createSession(home))
            withTimeout(25.seconds) { renewed.await() }
            views.release(view)
            assertEquals(2, keepalives)
            assertEquals(SessionViewStatus.Closed, view.status.value)
        }
    }

    test("output nonce switch discards old replay and same nonce never resubscribes") {
        frontend {
            val state = MutableStateFlow<AgentStateValue>(AgentStateValue.RequestResponse.Message(-7))
            val first = MutableSharedFlow<ResponsesStreamEvent>(replay = 10)
            val second = MutableSharedFlow<ResponsesStreamEvent>(replay = 10)
            val calls = MutableStateFlow(emptyList<Long>())
            val rpc = object : AgentRuntimeRpc by services.runtime {
                override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent> = flow {
                    calls.value += nonce
                    emitAll(if (nonce == -7L) first else second)
                }
            }
            val owner = Job(coroutineContext[Job])
            val local = CoroutineScope(coroutineContext + owner)
            try {
                val display = local.projectOutput(1, rpc, state)
                first.subscriptionCount.first { it == 1 }
                val oldEvent = ResponsesStreamEvent.OutputTextDelta("old", 0, 0, "old")
                first.emit(oldEvent)
                val old = assertIs<KodexAgentStateValue.RequestResponse.Message>(display.value).events
                assertEquals(oldEvent, old.first())
                state.value = AgentStateValue.RequestResponse.AgentMessage(-7)
                display.filterIsInstance<KodexAgentStateValue.RequestResponse.AgentMessage>().first()
                assertEquals(listOf(-7L), calls.value)
                state.value = AgentStateValue.RequestResponse.Message(0)
                second.subscriptionCount.first { it == 1 }
                val next = display.filterIsInstance<KodexAgentStateValue.RequestResponse.Message>().first()
                assertTrue(next.events.replayCache.isEmpty())
                assertEquals(0, first.subscriptionCount.value)
                val newEvent = ResponsesStreamEvent.OutputTextDelta("new", 1, 0, "new")
                second.emit(newEvent)
                assertEquals(newEvent, next.events.first())
                state.value = AgentStateValue.Compacting
                display.first { it == KodexAgentStateValue.Compacting }
                first.subscriptionCount.first { it == 0 }
                second.subscriptionCount.first { it == 0 }
                assertEquals(listOf(-7L, 0L), calls.value)
            } finally { owner.cancelAndJoin() }
        }
    }

    test("expired output waits for subscribed state without polling or reopening the old nonce") {
        frontend {
            val state = MutableStateFlow<AgentStateValue>(AgentStateValue.RequestResponse.Message(42))
            val requested = CompletableDeferred<Unit>()
            var calls = 0
            val rpc = object : AgentRuntimeRpc by services.runtime {
                override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent> = flow {
                    calls++
                    requested.complete(Unit)
                    throw NoMatchException()
                }
            }
            val owner = Job(coroutineContext[Job])
            try {
                val display = CoroutineScope(coroutineContext + owner).projectOutput(1, rpc, state)
                requested.await()
                state.value = AgentStateValue.ToolCompleted
                display.first { it == KodexAgentStateValue.ToolCompleted }
                assertEquals(1, calls)
                assertTrue(owner.isActive)
            } finally { owner.cancelAndJoin() }
        }
    }

    test("history confirmation retains its selected nonce rather than upgrading a stale target") {
        frontend {
            val view = views.open(services.global.createSession(home))
            val binding = view.current()
            val agent = requireNotNull(view.rootAgent.value)
            binding.submit(listOf(ContentItem.InputText("one")))
            binding.state.first { it == AgentStateValue.AssistantMessage }
            binding.running.first { !it }
            val index = 1
            val nonce = binding.storage.cacheNonce.value
            val request = agent.requestHistoryRevert(index, nonce)
            binding.revertHistory(index, nonce)
            binding.storage.cacheNonce.first { it != nonce }
            binding.state.first { it == AgentStateValue.Empty }
            agent.confirmHistoryRevert(request)
            failures.first { it.size == 1 }
            assertEquals(AgentStateValue.Empty, binding.state.value)
            assertEquals(request, assertIs<io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState.ConfirmRevert>(
                agent.historyAction.value,
            ).requestId)
            agent.dismissHistoryRevert(request)
            assertEquals(io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState.None, agent.historyAction.value)
        }
    }

    test("shell completion is a projection and completed entries remain until the registry removes them") {
        val arguments = ExecCommandArguments("not executed", yieldTimeMillis = 10_000)
        val snapshot = MutableStateFlow(mapOf(9 to ShellSessionState(arguments, "first")))
        val closed = CompletableDeferred<Unit>()
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                @Suppress("UNCHECKED_CAST")
                override suspend fun <T> call(call: RpcCall): T = when (call.callableName) {
                    "getShellSessions" -> snapshot.value as T
                    "closeShellSession" -> {
                        assertEquals("first", call.parameters.last())
                        closed.complete(Unit)
                        Unit as T
                    }
                    else -> delegate.call(call)
                }
                @Suppress("UNCHECKED_CAST")
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> =
                    if (call.callableName == "getShellSessionsFlow") snapshot as Flow<T>
                    else delegate.callServerStreaming(call)
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val registry = requireNotNull(view.rootAgent.value).shellSessions
            val shell = registry.activeSessions.value.getValue(9)
            shell.close()
            closed.await()
            assertFalse(shell.completed.value)
            snapshot.value = mapOf(9 to ShellSessionState(arguments, "first", 7))
            shell.completed.first { it }
            assertEquals(7, shell.exitCode.value)
            assertSame(shell, registry.activeSessions.value[9])
            snapshot.value = emptyMap()
            registry.activeSessions.first { it.isEmpty() }
            views.release(view)
        }
    }

    test("a read from a replaced registration is discarded and cancellation stays in the read scope") {
        val arguments = ExecCommandArguments("identical command", yieldTimeMillis = 10_000)
        val snapshot = MutableStateFlow(mapOf(9 to ShellSessionState(arguments, "first")))
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondReleased = CompletableDeferred<Unit>()
        var closeCalls = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                @Suppress("UNCHECKED_CAST")
                override suspend fun <T> call(call: RpcCall): T = when (call.callableName) {
                    "getShellSessions" -> snapshot.value as T
                    "readShellSessionOutput" -> {
                        when (call.parameters.last()) {
                            "first" -> { started.complete(Unit); resume.await() }
                            "second" -> {
                                secondStarted.complete(Unit)
                                try { awaitCancellation() } finally { secondReleased.complete(Unit) }
                            }
                            else -> error("Read did not pass a captured registration")
                        }
                        StdoutSlice(byteArrayOf(65), byteArrayOf(), 0uL, 0uL, 1uL, 1uL) as T
                    }
                    "closeShellSession" -> { closeCalls++; Unit as T }
                    else -> delegate.call(call)
                }
                @Suppress("UNCHECKED_CAST")
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> =
                    if (call.callableName == "getShellSessionsFlow") snapshot as Flow<T>
                    else delegate.callServerStreaming(call)
            }
        }) {
            val view = views.open(services.global.createSession(home))
            val registry = requireNotNull(view.rootAgent.value).shellSessions
            val old = registry.activeSessions.value.getValue(9)
            coroutineScope {
                val read = async {
                    assertFailsWith<ShellSessionNotRegistered> { old.readOutput(0uL, 8u) }
                }
                started.await()
                snapshot.value = mapOf(9 to ShellSessionState(arguments, "second"))
                val replacement = registry.activeSessions.first { it[9] !== old }.getValue(9)
                assertFalse(replacement.completed.value)
                old.close()
                resume.complete(Unit)
                read.await()
                assertFailsWith<ShellSessionNotRegistered> { old.readOutput(0uL, 8u) }
                val next = launch { replacement.readOutput(0uL, 8u) }
                secondStarted.await()
                next.cancelAndJoin()
                secondReleased.await()
                assertEquals(0, closeCalls)
            }
            views.release(view)
        }
    }

    test("closing a tab during its first activation releases the pending local binding") {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive") {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { released.complete(Unit) }
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val index = services.global.createSession(home)
            val opening = async { views.open(index) }
            entered.await()
            views.release(index)
            released.await()
            assertFailsWith<CancellationException> { opening.await() }
            assertEquals(index, services.global.getSessionCatalog(true).single().sessionIndex)
        }
    }

    test("revision-bound secret answer drafts submit through AskUserSnapshot and steer the Agent") {
        frontend {
            val sessionIndex = services.global.createSession(home)
            val view = views.open(sessionIndex)
            val created = services.runtime.createAskUser(
                sessionIndex,
                RequestUserInputArgs(
                    listOf(RequestUserInputQuestion("secret", "Secret", "Value?", isSecret = true)),
                ),
            )
            val questions = requireNotNull(view.rootAgent.value).requestUserInput
            val observed = questions.state.filterIsInstance<RequestUserInputState.Pending>().first()
            assertEquals(created.askId, observed.askId)
            assertTrue(observed.arguments.questions.single().isSecret)
            assertTrue(questions.updateFreeForm(observed.askId, "secret", "private answer"))
            val edited = assertIs<RequestUserInputState.Pending>(questions.state.value)
            questions.submit(observed.askId, observed.revision)
            assertEquals(edited, questions.state.value)
            questions.submit(edited.askId, edited.revision)
            view.current().state.first { it == AgentStateValue.AssistantMessage }
            view.current().running.first { !it }
            questions.state.first { it == RequestUserInputState.Idle }
            val answered = view.current().storage.getAskUsersAt(view.current().latestIndex.value).single()
            assertEquals(created.askId, answered.askId)
            assertEquals(listOf("user_note: private answer"), answered.answer?.response?.answers?.get("secret")?.answers)
        }
    }
}

internal class FrontendFixture(
    override val coroutineContext: kotlin.coroutines.CoroutineContext,
    val services: RpcServices,
    val views: RpcSessionViews,
    val global: RpcGlobalSettings,
    val home: Path,
    val failures: StateFlow<List<Throwable>>,
) : CoroutineScope {
    suspend fun create(name: String = "Test Session"): RpcSessionView {
        val session = views.open(services.global.createSession(home))
        session.rename(name)
        session.name.first { it == name }
        session.settings.first { it.threadName == name }
        return session
    }
}

internal suspend fun frontend(
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { answer() },
    decorate: (RpcClient) -> RpcClient = { it },
    login: OpenAiLoginClient = FixtureLoginClient(),
    onRequest: (ResponsesApiRequest) -> Unit = {},
    connectionAvailable: () -> Boolean = { true },
    block: suspend FrontendFixture.() -> Unit,
) = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(40.seconds) {
    // Interaction commands and child observations share a serialized owner dispatcher,
    // matching the component contracts rather than racing initial emissions on Default.
    val root = Path(SystemTemporaryDirectory, "kodex-rpc-frontend-${Random.nextLong()}")
    val client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { request -> onRequest(request); flow { response() } }
    }
    try {
        withBackendServices(
            root, Path(root, "codex"), Path(root, "agents"),
            defaults = defaultBackendSettings().let {
                it.copy(
                    sessionTitle = SessionTitleSettings(false),
                    newSession = it.newSession.copy(model = OpenAiModelId("test-model")),
                )
            },
            createClient = { client },
            createLoginClient = { login },
        ) { backend ->
            withInMemoryRpc(backend::register) { raw ->
                val services = RpcServices(decorate(RestoringRpcClient(raw)))
                val global = RpcGlobalSettings.open(services.global, this, 80)
                val failures = MutableStateFlow<List<Throwable>>(emptyList())
                val views = RpcSessionViews(this, services, MutableStateFlow(services.global.getModels()),
                    connectionAvailable = connectionAvailable,
                    reportUnhandledError = { error -> failures.update { it + error } })
                try { FrontendFixture(coroutineContext, services, views, global, root, failures).block() }
                finally {
                    views.close()
                    global.close()
                    withContext(NonCancellable) { try { views.join() } finally { global.join() } }
                }
            }
        }
    } finally {
        removeTestDirectory(root)
    }
} }

internal suspend fun removeTestDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeTestDirectory(it) }
    SystemCoroutineFileSystem.delete(path)
}

private class FixtureLoginClient : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        URLBuilder("https://login.example.invalid/authorize").apply {
            parameters.append("state", request.state)
        }.buildString()
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> =
        OpenAiResult.Success(
            OpenAiSubscriptionTokens("opaque-test-id", "test-access", "test-refresh", "test-account"),
        )
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> =
        error("Fresh test credentials should not refresh.")
}

internal suspend fun FlowCollector<ResponsesStreamEvent>.answer() {
    emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
        id = ResponseItemId("answer"), role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("answer")),
    )))
    emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
}
