package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionResult
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionResult
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskSubmissionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.DefaultSessionCatalogViewModel
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.ktor.http.URLBuilder
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.client.update
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
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
    test("blank drafts and catalog construction allocate nothing") {
        frontend {
            val draft = RpcSessionDraft(settings, views)
            draft.composer.update("draft")
            draft.edit { it.copy(instructions = "local") }
            val catalog = DefaultSessionCatalogViewModel(this, RpcSessionCatalogDependencies(services.global))
            try {
                assertEquals(SessionCatalogState.Unloaded, catalog.state.value)
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                assertNull(draft.persistedIndex)
                catalog.refresh()
                assertTrue(catalog.state.value.sessions.isEmpty())
            } finally { catalog.close(); draft.close() }
        }
    }

    test("concurrent opens share a view and local release leaves backend and connection alive") {
        frontend {
            val index = services.global.createSession(settings)
            val first = async { views.open(index) }
            val second = async { views.open(index) }
            val view = first.await()
            assertSame(view, second.await())
            assertEquals(SessionViewStatus.Ready, view.status.value)
            val originalNonce = view.current().storage.index.cacheNonce.value
            views.release(view)
            assertEquals(SessionViewStatus.Closed, view.status.value)
            assertTrue(services.global.getSessionCatalog(false).single().isActive)
            val reopened = views.open(index)
            assertNotSame(view, reopened)
            assertEquals(originalNonce, reopened.current().storage.index.cacheNonce.value)
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
            val index = services.global.createSession(settings)
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
            val index = services.global.createSession(settings)
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
            val index = services.global.createSession(settings)
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
            val index = services.global.createSession(settings)
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
            val index = services.global.createSession(settings)
            val view = views.open(index)
            val binding = view.current()
            services.global.deleteSession(index)
            view.status.first { it == SessionViewStatus.Missing }
            assertNull(view.binding.value)
            assertFailsWith<CancellationException> { binding.storage.settings.get(0) }
            assertTrue(services.global.getSessionCatalog(true).isEmpty())
            views.release(view)
        }
    }

    test("settings and token projection observe real values and successful CAS never fabricates a reply value") {
        frontend {
            val view = views.open(services.global.createSession(settings))
            val binding = view.current()
            val initial = binding.settings.value
            assertEquals(0, binding.latestIndex.value)
            assertEquals(0L, binding.tokenCount.value?.totalTokens)
            assertTrue(binding.settings.compareAndSet(initial, initial.copy(instructions = "edited")))
            binding.settings.first { it.instructions == "edited" }
            assertFalse(binding.settings.compareAndSet(initial, initial.copy(threadName = "stale")))
            assertEquals("Session ${view.index}", services.settings.get(
                view.index, binding.storage.settings.cacheNonce.value, binding.latestIndex.value,
            ).threadName)
        }
    }

    test("append and rollback refresh the original history using the backend nonce") {
        frontend {
            val view = views.open(services.global.createSession(settings))
            val binding = view.current()
            val agent = requireNotNull(view.agent.value)
            val committed = binding.appendUserMessage(listOf(ContentItem.InputText("history")))
            val old = agent.historyIndex.window.first { committed in it.indexes }
            assertEquals(binding.storage.index.cacheNonce.value, old.generation)
            assertTrue(agent.historyIndex.load(old.generation, committed).summary.contains("history"))
            agent.history.historyItems.first { it.size > 0 }
            assertTrue(agent.historyIndex.checkOut(old.generation, committed))
            assertEquals(committed, binding.latestIndex.value) // Check out did not revert history.
            binding.revertHistory(committed, old.generation)
            val changed = agent.historyIndex.window.first { it.generation != old.generation }
            assertEquals(binding.storage.index.cacheNonce.value, changed.generation)
            assertFalse(agent.historyIndex.contains(old.generation, committed))
            assertFalse(agent.historyIndex.checkOut(old.generation, committed))
            assertFailsWith<CacheNonceMismatch> { binding.forkHistory(1, old.generation) }
            binding.settings.first { it.threadName == "Session ${view.index}" }
        }
    }

    test("catalog dates use the snapshot while mutations are backend commands") {
        frontend {
            val index = services.global.createSession(settings)
            val catalog = DefaultSessionCatalogViewModel(this, RpcSessionCatalogDependencies(services.global))
            try {
                catalog.refresh()
                val entry = catalog.state.value.sessions.single()
                assertEquals(entry.createdAt, catalog.readCreatedAt(index))
                assertEquals(entry.updatedAt, catalog.readUpdatedAt(index))
                catalog.archive(index)
                assertTrue(catalog.state.value.sessions.isEmpty())
                catalog.setShowArchived(true)
                assertTrue(catalog.state.value.sessions.single().archived)
                catalog.unarchive(index)
                val fork = catalog.fork(index)
                assertNotEquals(index, fork)
                assertTrue(catalog.delete(fork))
                assertEquals(index, catalog.state.value.sessions.single().sessionIndex)
            } finally { catalog.close() }
        }
    }

    test("runtime component binds exact Agent and atomically preserves unrelated fields") {
        frontend {
            val view = views.open(services.global.createSession(settings.copy(threadName = "fixed", instructions = "keep")))
            val other = views.open(services.global.createSession(settings.copy(threadName = "other")))
            val agent = requireNotNull(view.agent.value)
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
                assertEquals(settings.model, other.current().settings.value.model)
                child.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
                agent.settings.first { it.requestUserInputMode == RequestUserInputMode.NoQuestion }
                agent.close()
                assertTrue(child.state.value.closed)
                child.updateModelConfiguration(OpenAiModelId("late"), ReasoningEffort.Low, ServiceTier.Default)
                assertEquals(OpenAiModelId("edited"), view.current().settings.value.model)
                assertEquals(settings.model, other.current().settings.value.model)
            } finally { agent.close() }
        }
    }

    test("bound Composer stale and closed submissions never issue an RPC command") {
        var appends = 0
        var resumes = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "appendUserMessage") appends++
                    if (call.callableName == "resume") resumes++
                    return delegate.call(call)
                }
            }
        }) {
            val view = views.open(services.global.createSession(settings))
            val composer = requireNotNull(view.agent.value).composer
            val original = composer.update("original")
            val current = composer.update("current")
            assertEquals(ComposerSubmissionResult.Stale, composer.submit(original))
            assertEquals("current", composer.state.value.text)
            assertEquals(current, composer.state.value.revision)
            views.release(view)
            assertEquals(ComposerLifecycle.Closed, composer.state.value.lifecycle)
            assertEquals(ComposerSubmissionResult.Unavailable, composer.submit(current))
            assertEquals(SessionViewStatus.Closed, view.status.value)
            assertEquals(0, appends)
            assertEquals(0, resumes)
        }
    }

    test("cancelled caller of bound Composer append restores editing without Stop or Agent failure") {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var appends = 0
        var resumes = 0
        var stops = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    when (call.callableName) {
                        "appendUserMessage" -> {
                            appends++
                            entered.complete(Unit)
                            try { awaitCancellation() } finally { cancelled.complete(Unit) }
                        }
                        "resume" -> resumes++
                        "cancelRunningTurn" -> stops++
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val view = views.open(services.global.createSession(settings))
            val binding = view.current()
            val agent = requireNotNull(view.agent.value)
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
            assertNull(agent.notification.value)
            assertEquals(SessionViewStatus.Ready, view.status.value)
            assertSame(binding, view.current())
            assertEquals(1, appends)
            assertEquals(0, resumes)
            assertEquals(0, stops)
        }
    }

    test("bound Composer lost append reply reports once without rollback retry or draft loss") {
        var appends = 0
        var resumes = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "resume") resumes++
                    val result = delegate.call<T>(call)
                    if (call.callableName == "appendUserMessage") {
                        appends++
                        error("accepted append reply lost")
                    }
                    return result
                }
            }
        }) {
            val view = views.open(services.global.createSession(settings))
            val agent = requireNotNull(view.agent.value)
            try {
                val revision = agent.composer.update("  persisted but retained  ", 6)
                assertEquals(
                    ComposerSubmissionResult.Failed("accepted append reply lost"),
                    agent.composer.submit(revision),
                )
                val notification = agent.notification.filterNotNull().first()
                assertEquals(1L, notification.id)
                assertEquals("Session operation failed.", notification.message)
                assertEquals("accepted append reply lost", notification.detail)
                assertEquals("  persisted but retained  ", agent.composer.state.value.text)
                assertEquals(6, agent.composer.state.value.cursorOffset)
                assertEquals(revision + 1, agent.composer.state.value.revision)
                assertEquals(AgentStateValue.UserMessage, services.runtime.getState(view.index))
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertEquals(1, appends)
                assertEquals(0, resumes)
            } finally { agent.close() }
        }
    }

    test("root Stop and Composer cancel share the existing caught ordinary-failure boundary") {
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
            val view = views.open(services.global.createSession(settings))
            val binding = view.current()
            val agent = requireNotNull(view.agent.value)
            try {
                agent.cancel() // Actual root Stop command, not the Composer cancel port.
                val first = agent.notification.filterNotNull().first()
                assertEquals("ordinary stop failure", first.detail)
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertSame(binding, view.current())
                agent.dismissNotification(first.id)
                assertNull(agent.notification.value)
                // The bound Composer callback also uses the caught Agent command boundary.
                agent.composer.cancel()
                agent.notification.filterNotNull().first { it.id > first.id }
                assertEquals(2, stops)
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertSame(binding, view.current())
            } finally { agent.close() }
        }
    }

    test("composer CAS queues while running and closing the tab does not stop the accepted turn") {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        frontend(response = {
            entered.complete(Unit)
            finish.await()
            answer()
        }) {
            val view = views.open(services.global.createSession(settings))
            val binding = view.current()
            binding.appendUserMessage(listOf(ContentItem.InputText("start")))
            val waiting = async { binding.resume() }
            entered.await()
            binding.running.first { it }
            val agent = requireNotNull(view.agent.value)
            agent.composer.state.first { it.running }
            val revision = agent.composer.update("steer")
            assertEquals(ComposerSubmissionResult.QueuedAsSteer, agent.composer.submit(revision))
            binding.pendingSteer.first { it.isNotEmpty() }
            binding.pendingSteer.update { emptyList() }
            binding.pendingSteer.first { it.isEmpty() }
            waiting.cancelAndJoin()
            views.release(view)
            assertTrue(services.runtime.getRunningTurn(view.index))
            finish.complete(Unit)
            services.runtime.getRunningTurnFlow(view.index).first { !it }
            assertEquals(AgentStateValue.AssistantMessage, services.runtime.getState(view.index))
        }
    }

    test("new draft is materialized once and returns the existing view after successful initial submission") {
        frontend {
            val draft = RpcSessionDraft(settings, views)
            try {
                draft.composer.update("first")
                val view = draft.materialize()
                assertEquals(view.index, draft.persistedIndex)
                assertEquals("", draft.composer.state.value.text)
                view.current().state.first { it == AgentStateValue.AssistantMessage }
                assertSame(view, draft.materialize())
                assertEquals(1, services.global.getSessionCatalog(true).size)
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
            val view = views.open(services.global.createSession(settings))
            val binding = view.current()
            binding.appendUserMessage(listOf(ContentItem.InputText("stream please")))
            val waiting = async { binding.resume() }
            emitted.await()
            val output = binding.displayState.filterIsInstance<KodexAgentStateValue.RequestResponse.Message>().first()
            val first = async { output.events.first { it is ResponsesStreamEvent.OutputTextDelta } }
            val second = async { output.events.first { it is ResponsesStreamEvent.OutputTextDelta } }
            assertEquals(first.await(), second.await())
            views.release(view)
            finish.complete(Unit)
            waiting.await()
            assertEquals(SessionViewStatus.Closed, view.status.value)
        }
    }

    test("an inactive upstream rebuilds all bindings without replaying an accepted append") {
        val fail = CompletableDeferred<Unit>()
        var subscribed = false
        var keepalives = 0
        var appends = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "keepSessionAlive") keepalives++
                    if (call.callableName == "appendUserMessage") appends++
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
            val view = views.open(services.global.createSession(settings))
            val old = view.current()
            val oldAgent = requireNotNull(view.agent.value)
            val oldComposer = oldAgent.composer
            old.appendUserMessage(listOf(ContentItem.InputText("once")))
            fail.complete(Unit)
            val recovered = view.binding.filterNotNull().first { it !== old }
            view.status.first { it == SessionViewStatus.Ready }
            val recoveredAgent = requireNotNull(view.agent.value)
            assertNotSame(oldAgent, recoveredAgent)
            assertNotSame(oldComposer, recoveredAgent.composer)
            assertSame(recovered.settings, recoveredAgent.settings)
            oldAgent.lifecycle.first { it == io.github.stream29.kodex.app.agent.contract.AgentLifecycleState.Closed }
            assertEquals(ComposerLifecycle.Closed, oldComposer.state.value.lifecycle)
            assertEquals(ComposerSubmissionResult.Unavailable, oldComposer.submit(oldComposer.state.value.revision))
            assertFailsWith<CancellationException> { oldAgent.renameThread("expired binding") }
            assertSame(recoveredAgent, view.agent.value)
            assertEquals(2, keepalives)
            assertEquals(1, appends)
            assertEquals(AgentStateValue.UserMessage, recovered.state.value)
            assertFailsWith<CancellationException> { old.storage.settings.get(0) }
            assertSame(view, views.open(view.index))
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
            val view = views.open(services.global.createSession(settings))
            fail.complete(Unit)
            reactivating.await()
            views.release(view)
            assertEquals(SessionViewStatus.Closed, view.status.value)
            assertNull(view.binding.value)
            assertNull(view.agent.value)
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
            val view = views.open(services.global.createSession(settings))
            fail.complete(Unit)
            val failure = view.status.filterIsInstance<SessionViewStatus.Failed>().first()
            assertEquals("observer failure", failure.cause.message)
            assertNull(view.binding.value)
            assertEquals(1, keepalives)
            assertFailsWith<IllegalStateException> { view.current() }
        }
    }

    test("a lost append reply keeps the created entity and draft without automatic retries") {
        var appends = 0
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    val result = delegate.call<T>(call)
                    if (call.callableName == "appendUserMessage") {
                        appends++
                        error("lost reply")
                    }
                    return result
                }
            }
        }) {
            val draft = RpcSessionDraft(settings, views)
            try {
                draft.composer.update("keep me")
                assertFailsWith<IllegalStateException> { draft.materialize() }
                val index = assertNotNull(draft.persistedIndex)
                assertEquals(index, services.global.getSessionCatalog(true).single().sessionIndex)
                assertEquals("keep me", draft.composer.state.value.text)
                assertEquals(AgentStateValue.UserMessage, services.runtime.getState(index))
                assertEquals(1, appends)
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
            val view = views.open(services.global.createSession(settings))
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
            val view = views.open(services.global.createSession(settings))
            val binding = view.current()
            val agent = requireNotNull(view.agent.value)
            val index = binding.appendUserMessage(listOf(ContentItem.InputText("one")))
            binding.latestIndex.first { it >= index }
            binding.state.first { it == AgentStateValue.UserMessage }
            val nonce = binding.storage.index.cacheNonce.value
            val request = agent.requestHistoryRevert(index, nonce)
            binding.revertHistory(index, nonce)
            binding.storage.index.cacheNonce.first { it != nonce }
            binding.state.first { it == AgentStateValue.Empty }
            agent.confirmHistoryRevert(request)
            agent.notification.filterNotNull().first()
            assertEquals(AgentStateValue.Empty, binding.state.value)
            assertEquals(request, assertIs<io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState.ConfirmRevert>(
                agent.historyAction.value,
            ).requestId)
            agent.dismissHistoryRevert(request)
            assertEquals(io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState.None, agent.historyAction.value)
        }
    }

    test("shell completion is a projection and completed entries remain until the registry removes them") {
        val arguments = ExecCommandArguments("not executed")
        val snapshot = MutableStateFlow(mapOf(9 to ShellSessionState(arguments, false)))
        val closed = CompletableDeferred<Unit>()
        frontend(decorate = { delegate ->
            object : RpcClient by delegate {
                @Suppress("UNCHECKED_CAST")
                override suspend fun <T> call(call: RpcCall): T = when (call.callableName) {
                    "getShellSessions" -> snapshot.value as T
                    "closeShellSession" -> { closed.complete(Unit); Unit as T }
                    else -> delegate.call(call)
                }
                @Suppress("UNCHECKED_CAST")
                override fun <T> callServerStreaming(call: RpcCall): Flow<T> =
                    if (call.callableName == "getShellSessionsFlow") snapshot as Flow<T>
                    else delegate.callServerStreaming(call)
            }
        }) {
            val view = views.open(services.global.createSession(settings))
            val registry = requireNotNull(view.agent.value).shellSessions
            val shell = registry.activeSessions.value.getValue(9)
            shell.close()
            closed.await()
            assertFalse(shell.completed.value)
            snapshot.value = mapOf(9 to ShellSessionState(arguments, true))
            shell.completed.first { it }
            assertSame(shell, registry.activeSessions.value[9])
            snapshot.value = emptyMap()
            registry.activeSessions.first { it.isEmpty() }
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
            val index = services.global.createSession(settings)
            val opening = async { views.open(index) }
            entered.await()
            views.release(index)
            released.await()
            assertFailsWith<CancellationException> { opening.await() }
            assertEquals(index, services.global.getSessionCatalog(true).single().sessionIndex)
        }
    }

    test("revision-bound secret answer drafts submit through the original tool completion contract") {
        val pending = PendingRequestUserInputToolEvent("question", arguments = RequestUserInputArgs(
            listOf(RequestUserInputQuestion("secret", "Secret", "Value?", isSecret = true)),
        ))
        val firstRequest = MutableStateFlow(true)
        frontend(response = {
            if (firstRequest.compareAndSet(true, false)) {
                emit(ResponsesStreamEvent.OutputItemDone(0, pending.toResponseHistoryItems().single() as ResponseItem.ToolCall))
                emit(ResponsesStreamEvent.Completed(Response(id = "pending", endTurn = true)))
            } else answer()
        }) {
            val view = views.open(services.global.createSession(settings))
            view.current().appendUserMessage(listOf(ContentItem.InputText("question please")))
            view.current().resume()
            val questions = requireNotNull(view.agent.value).requestUserInput
            val observed = questions.state.filterIsInstance<RequestUserInputState.Pending>().first()
            assertTrue(observed.arguments.questions.single().isSecret)
            assertTrue(questions.updateFreeForm(observed.callId, "secret", "private answer"))
            val edited = assertIs<RequestUserInputState.Pending>(questions.state.value)
            assertEquals(RequestUserInputSubmissionResult.StaleRevision, questions.submit(observed.callId, observed.revision))
            assertEquals(RequestUserInputSubmissionResult.Submitted, questions.submit(edited.callId, edited.revision))
            view.current().state.first { it == AgentStateValue.AssistantMessage }
            assertEquals(RequestUserInputState.Idle, questions.state.value)
        }
    }

    test("suggestion confirmation creates children in one RPC and submits the parent tool separately") {
        val pending = PendingSuggestSubagentTaskToolEvent("suggest", arguments = SuggestSubagentTaskArgs(
            listOf(SuggestedSubagentTask("one", "first"), SuggestedSubagentTask("two", "second")),
        ))
        val firstRequest = MutableStateFlow(true)
        var batchCalls = 0
        var createCalls = 0
        var completeCalls = 0
        frontend(
            response = {
                if (firstRequest.compareAndSet(true, false)) {
                    emit(ResponsesStreamEvent.OutputItemDone(0, pending.toResponseHistoryItems().single() as ResponseItem.ToolCall))
                    emit(ResponsesStreamEvent.Completed(Response(id = "pending", endTurn = true)))
                } else answer()
            },
            decorate = { delegate ->
                object : RpcClient by delegate {
                    override suspend fun <T> call(call: RpcCall): T {
                        when (call.callableName) {
                            "createSession" -> createCalls++
                            "createSuggestedSessions" -> batchCalls++
                            "completeToolCall" -> completeCalls++
                        }
                        return delegate.call(call)
                    }
                }
            },
        ) {
            val view = views.open(services.global.createSession(settings))
            view.current().appendUserMessage(listOf(ContentItem.InputText("children please")))
            view.current().resume()
            val suggestions = requireNotNull(view.agent.value).suggestSubagentTask
            val observed = suggestions.state.filterIsInstance<SuggestSubagentTaskState.Pending>().first()
            assertEquals(SuggestSubagentTaskSubmissionResult.Submitted, suggestions.submit(observed.callId, observed.revision, true))
            assertEquals(1, batchCalls)
            assertEquals(1, createCalls)
            assertEquals(1, completeCalls)
            assertEquals(3, services.global.getSessionCatalog(true).size)
            view.current().state.first { it == AgentStateValue.AssistantMessage }
        }
    }
}

private val settings = KodexAgentSettings(OpenAiModelId("test-model"))

internal class FrontendFixture(
    override val coroutineContext: kotlin.coroutines.CoroutineContext,
    val services: RpcServices,
    val views: RpcSessionViews,
    val home: Path,
) : CoroutineScope

internal suspend fun frontend(
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { answer() },
    decorate: (RpcClient) -> RpcClient = { it },
    login: OpenAiLoginClient = FixtureLoginClient(),
    block: suspend FrontendFixture.() -> Unit,
) = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(40.seconds) {
    // Interaction commands and child observations share a serialized owner dispatcher,
    // matching the component contracts rather than racing initial emissions on Default.
    val root = Path(SystemTemporaryDirectory, "kodex-rpc-frontend-${Random.nextLong()}")
    val client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { flow { response() } }
    }
    try {
        withBackendServices(
            root, Path(root, "codex"), Path(root, "agents"),
            defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
            createClient = { client },
            createLoginClient = { login },
        ) { backend ->
            withInMemoryRpc(backend::register) { raw ->
                val services = RpcServices(decorate(RestoringRpcClient(raw)))
                val views = RpcSessionViews(this, services, MutableStateFlow(services.global.getModels()))
                try { FrontendFixture(coroutineContext, services, views, root).block() }
                finally { views.close(); withContext(NonCancellable) { views.join() } }
            }
        }
    } finally {
        suspend fun remove(path: Path) {
            val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
            if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { remove(it) }
            SystemCoroutineFileSystem.delete(path)
        }
        remove(root)
    }
} }

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

private suspend fun FlowCollector<ResponsesStreamEvent>.answer() {
    emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
        id = ResponseItemId("answer"), role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("answer")),
    )))
    emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
}
