package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReasoning
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerationResult
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.client.rpcCachedIndexVersioned
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.rpcexception.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.withService
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds

val backendRuntimeRpcTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("six concrete services retain real sparse values and frontend cache semantics") {
        runtimeFixture { host, index, _ ->
            withInMemoryRpc(registerServices = {
                registerService(IndexTimelineRpc::class) { BackendIndexTimelineRpc(host) }
                registerService(WorkTimelineRpc::class) { BackendWorkTimelineRpc(host) }
                registerService(SettingsTimelineRpc::class) { BackendSettingsTimelineRpc(host) }
                registerService(TimestampTimelineRpc::class) { BackendTimestampTimelineRpc(host) }
                registerService(TokenCountTimelineRpc::class) { BackendTokenCountTimelineRpc(host) }
                registerService(UnstableTimelineRpc::class) { BackendUnstableTimelineRpc(host) }
            }) { raw ->
                val client = RestoringRpcClient(raw)
                val runtime = host.session(index).session.runtime
                runtime.appendUserMessage(listOf(ContentItem.InputText("one")))
                runtime.modify {
                    val next = it.latestIndex() + 1
                    it.work[next] = StableReasoning(ResponseItem.Reasoning(encryptedContent = "test-reasoning"))
                    it.unstable[next] = emptyList()
                    it.tokenCount[next] = TokenCountSnapshot(
                        TokenCountKind.Response, 0, TokenUsage(0, 0, 0),
                        TokenCountDiagnostics(requestId = "test", turnStateReceived = true),
                    )
                }
                val storage = runtime.storage as ObservableKodexAgentStorage
                checkTimeline(index, client.withService<IndexTimelineRpc>(), storage.index)
                checkTimeline(index, client.withService<WorkTimelineRpc>(), storage.work)
                checkTimeline(index, client.withService<SettingsTimelineRpc>(), storage.settings)
                checkTimeline(index, client.withService<TimestampTimelineRpc>(), storage.timestamp)
                checkTimeline(index, client.withService<TokenCountTimelineRpc>(), storage.tokenCount)
                checkTimeline(index, client.withService<UnstableTimelineRpc>(), storage.unstable)
                val unstable = client.withService<UnstableTimelineRpc>()
                val nonce = unstable.getCacheNonce(index)
                assertEquals(emptyList(), unstable.getExact(index, nonce, runtime.latestIndex.value))
                assertNull(unstable.getExact(index, nonce, 999))
            }
        }
    }

    test("settings CAS uses actual current value and equal writes leave the tail unchanged") {
        runtimeFixture { host, index, _ ->
            val rpc = BackendSettingsTimelineRpc(host)
            val nonce = rpc.getCacheNonce(index)
            val old = rpc.get(index, nonce, rpc.getLatestIndex(index))
            val tail = rpc.getLatestIndex(index)
            assertTrue(rpc.compareAndSet(index, old, old))
            assertEquals(tail, rpc.getLatestIndex(index))
            assertTrue(rpc.compareAndSet(index, old, old.copy(threadName = "changed")))
            assertFalse(rpc.compareAndSet(index, old, old.copy(threadName = "stale")))
            assertEquals(nonce, rpc.getCacheNonce(index))
        }
    }

    test("revert checks nonce at write admission clears steer and invalidates the frontend cache") {
        runtimeFixture { host, index, rpc ->
            val timelines = BackendIndexTimelineRpc(host)
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("old")))
            val nonce = timelines.getCacheNonce(index)
            assertTrue(rpc.compareAndSetPendingSteer(index, emptyList(), listOf(StableUserMessage(listOf(ContentItem.InputText("queued"))))))
            assertFailsWith<CacheNonceMismatch> { rpc.revertHistory(index, 1, nonce xor 1) }
            assertFailsWith<IllegalArgumentException> { rpc.revertHistory(index, 0, nonce) }
            rpc.revertHistory(index, 1, nonce)
            assertEquals(0, rpc.getLatestIndex(index))
            assertEquals(emptyList(), rpc.getPendingSteer(index))
            assertNotEquals(nonce, timelines.getCacheNonce(index))
            assertFailsWith<CacheNonceMismatch> { timelines.getExact(index, nonce, 1) }
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("replacement")))
            assertEquals("replacement", (timelines.getExact(index, timelines.getCacheNonce(index), 1) as StableUserMessage).content.filterIsInstance<ContentItem.InputText>().single().text)
        }
    }

    test("owner disappearance ends old RPC subscriptions without ending the connection") {
        runtimeFixture { host, index, backend ->
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                val started = CompletableDeferred<Unit>()
                val subscription = async {
                    assertFailsWith<SessionNotActive> {
                        rpc.getLatestIndexFlow(index).onEach { started.complete(Unit) }.collect()
                    }
                }
                started.await()
                assertTrue(host.deleteSession(index))
                subscription.await()
                assertFailsWith<SessionNotActive> { rpc.getLatestIndex(index) }
                assertFailsWith<SessionNotFound> { host.keepSessionAlive(index) }
                val replacement = host.repository().create()
                host.keepSessionAlive(replacement)
                assertEquals(-1, rpc.getLatestIndex(replacement))
            }
        }
    }

    test("stream nonce binds replay and live collection to one original output") {
        val item = ResponseItem.Message(id = ResponseItemId("message"), role = MessageRole.Assistant, content = emptyList())
        val added = ResponsesStreamEvent.OutputItemAdded(0, item)
        val release = CompletableDeferred<Unit>()
        val done = ResponsesStreamEvent.OutputItemDone(0, item.copy(content = listOf(ContentItem.OutputText("answer"))))
        val client = mockOpenAiClient {
            createResponse { flow {
                emit(added)
                release.await()
                emit(done)
                emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
            } }
        }
        runtimeFixture(client) { host, index, backend ->
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                rpc.appendUserMessage(index, listOf(ContentItem.InputText("question")))
                val waiter = async { rpc.resume(index) }
                val active = rpc.getStateFlow(index).filterIsInstance<AgentStateValue.RequestResponse.Message>().first()
                val replay = rpc.currentFlow(index, active.nonce).first()
                assertEquals(added, replay)
                assertEquals(active, rpc.getState(index))
                assertTrue(rpc.getRunningTurn(index))
                assertFailsWith<NoMatchException> { rpc.currentFlow(index, active.nonce xor 1).first() }
                val values = async(start = CoroutineStart.UNDISPATCHED) { rpc.currentFlow(index, active.nonce).take(2).toList() }
                waiter.cancelAndJoin()
                assertTrue(host.session(index).session.runtime.runningTurn.value != null)
                release.complete(Unit)
                assertEquals(listOf(added, done), values.await())
                rpc.getRunningTurnFlow(index).first { !it }
                assertFailsWith<NoMatchException> { rpc.currentFlow(index, active.nonce).first() }
                assertEquals(AgentStateValue.AssistantMessage, rpc.getState(index))
            }
        }
    }

    test("stop cancels current operation while a cancelled waiter does not") {
        val entered = CompletableDeferred<Unit>()
        val client = mockOpenAiClient { createResponse { flow {
            entered.complete(Unit)
            awaitCancellation()
        } } }
        runtimeFixture(client) { _, index, rpc ->
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("run")))
            val waiter = async { rpc.resume(index) }
            entered.await()
            assertFailsWith<IllegalStateException> { rpc.resume(index) }
            waiter.cancelAndJoin()
            assertTrue(rpc.getRunningTurn(index))
            rpc.cancelRunningTurn(index)
            rpc.getRunningTurnFlow(index).first { !it }
            rpc.cancelRunningTurn(index)
            assertEquals(AgentStateValue.UserMessage, rpc.getState(index))
        }
    }

    test("manual compaction shares Stop and does not consume queued steer") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = mockOpenAiClient { createRemoteCompactionV2Response { _ ->
            entered.complete(Unit)
            release.await()
            RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "test"), null)
        } }
        runtimeFixture(client) { _, index, rpc ->
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("compact")))
            val operation = async { rpc.forcedCompact(index) }
            entered.await()
            assertEquals(AgentStateValue.Compacting, rpc.getState(index))
            assertTrue(rpc.getRunningTurn(index))
            assertFailsWith<NoMatchException> { rpc.currentFlow(index, 0).first() }
            assertFailsWith<IllegalStateException> { rpc.resume(index) }
            val pending = listOf(StableUserMessage(listOf(ContentItem.InputText("queued"))))
            assertTrue(rpc.compareAndSetPendingSteer(index, emptyList(), pending))
            release.complete(Unit)
            assertTrue(operation.await() > 1)
            assertEquals(pending, rpc.getPendingSteer(index))
            assertFalse(rpc.getRunningTurn(index))
        }
    }

    test("real RPC settings CAS accepted during compaction survives its published checkpoint") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = mockOpenAiClient {
            createRemoteCompactionV2Response {
                entered.complete(Unit)
                release.await()
                RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compacted"), null)
            }
        }
        runtimeFixture(client) { host, index, backendRuntime ->
            withInMemoryRpc(registerServices = {
                registerService(AgentRuntimeRpc::class) { backendRuntime }
                registerService(SettingsTimelineRpc::class) { BackendSettingsTimelineRpc(host) }
            }) { raw ->
                val restored = RestoringRpcClient(raw)
                val runtime = restored.withService<AgentRuntimeRpc>()
                val settings = restored.withService<SettingsTimelineRpc>()
                runtime.appendUserMessage(index, listOf(ContentItem.InputText("compact")))
                val before = settings.get(index, settings.getCacheNonce(index), settings.getLatestIndex(index))
                val operation = async { runtime.forcedCompact(index) }
                try {
                    entered.await()
                    val first = before.copy(threadName = "accepted-during-compaction")
                    assertTrue(settings.compareAndSet(index, before, first))
                    val latest = first.copy(model = OpenAiModelId("updated-model"))
                    assertTrue(settings.compareAndSet(index, first, latest))
                    release.complete(Unit)
                    val checkpoint = operation.await()
                    val published = settings.get(index, settings.getCacheNonce(index), checkpoint)
                    assertEquals(latest.threadName, published.threadName)
                    assertEquals(latest.model, published.model)
                    // The returned index is the compaction output; settings
                    // belong to its immediately preceding compaction point.
                    assertEquals(checkpoint - 1, settings.getLatestIndexFlow(index).first {
                        it == checkpoint - 1
                    })
                    assertEquals(AgentStateValue.UserMessage, runtime.getState(index))
                } finally {
                    release.complete(Unit)
                    operation.cancelAndJoin()
                }
            }
        }
    }

    test("shell completion is observed without registry membership changes") {
        runtimeFixture { host, index, rpc ->
            val shell = host.session(index).session.runtime.unifiedExecToolClient
            val result = withContext(Dispatchers.Default) {
                shell.execCommand(io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments(
                    command = "read ignored", yieldTimeMillis = 250,
                ))
            }
            val id = assertNotNull(result.sessionId)
            val started = CompletableDeferred<Unit>()
            val completed = async {
                rpc.getShellSessionsFlow(index).onEach {
                    if (it[id]?.completed == false) started.complete(Unit)
                }.first { it[id]?.completed == true }
            }
            started.await()
            rpc.closeShellSession(index, id)
            assertTrue(completed.await().getValue(id).completed)
            assertTrue(id in rpc.getShellSessions(index))
            assertFailsWith<IllegalArgumentException> { rpc.closeShellSession(index, id + 999) }
        }
    }

    test("projection changes nonce only for stream identity and never offers compaction output") {
        val projection = RuntimeOutputProjection()
        val first = MutableSharedFlow<ResponsesStreamEvent>(replay = 4)
        val second = MutableSharedFlow<ResponsesStreamEvent>(replay = 4)
        val a = projection.snapshot { KodexAgentStateValue.RequestResponse.Message(first) }
        val b = projection.snapshot { KodexAgentStateValue.RequestResponse.Message(first) }
        assertEquals(a.nonce, b.nonce)
        assertSame(first, a.events)
        val c = projection.snapshot { KodexAgentStateValue.RequestResponse.Reasoning(second) }
        assertNotEquals(a.nonce, c.nonce)
        val compact = projection.snapshot { KodexAgentStateValue.Compacting }
        assertEquals(AgentStateValue.Compacting, compact.value)
        assertNull(compact.events)
        assertNull(projection.snapshot { KodexAgentStateValue.RequestResponse.Started }.events)
    }

    test("automatic title preserves a winning manual name without repeating the model") {
        val entered = CompletableDeferred<Unit>()
        val titleJob = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        runtimeFixture(generator = SessionTitleGenerator { _, _, _ ->
            calls++
            titleJob.complete(currentCoroutineContext().job)
            entered.complete(Unit)
            release.await()
            SessionTitleGenerationResult.Generated("automatic")
        }, settings = SessionTitleSettings()) { host, index, rpc ->
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("title")))
            entered.await()
            val settings = BackendSettingsTimelineRpc(host)
            val nonce = settings.getCacheNonce(index)
            val old = settings.get(index, nonce, settings.getLatestIndex(index))
            assertTrue(settings.compareAndSet(index, old, old.copy(threadName = "manual")))
            release.complete(Unit)
            titleJob.await().join()
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("second")))
            assertEquals("manual", settings.get(index, nonce, settings.getLatestIndex(index)).threadName)
            assertEquals(1, calls)
        }
    }
    test("closing the backend Session owner cancels its pending title request") {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val job = CompletableDeferred<Job>()
        runtimeFixture(generator = SessionTitleGenerator { _, _, _ ->
            job.complete(currentCoroutineContext().job)
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }, settings = SessionTitleSettings()) { host, index, rpc ->
            assertEquals(1, rpc.appendUserMessage(index, listOf(ContentItem.InputText("Pending title"))))
            entered.await()
            assertTrue(host.deleteSession(index))
            stopped.await()
            job.await().join()
            assertTrue(job.await().isCancelled)
            assertFailsWith<SessionNotActive> { rpc.getLatestIndex(index) }
        }
    }

    test("automatic title keeps unrelated setting updates and failed titles do not fail append") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runtimeFixture(generator = SessionTitleGenerator { _, _, _ ->
            entered.complete(Unit)
            release.await()
            SessionTitleGenerationResult.Generated("automatic")
        }, settings = SessionTitleSettings()) { host, index, rpc ->
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("title")))
            entered.await()
            val settings = BackendSettingsTimelineRpc(host)
            val nonce = settings.getCacheNonce(index)
            val old = settings.get(index, nonce, settings.getLatestIndex(index))
            val changed = old.copy(instructions = "new instructions")
            assertTrue(settings.compareAndSet(index, old, changed))
            release.complete(Unit)
            settings.getLatestIndexFlow(index).first {
                settings.get(index, nonce, it).threadName == "automatic"
            }
            assertEquals(changed.instructions, settings.get(index, nonce, settings.getLatestIndex(index)).instructions)
        }
        runtimeFixture(generator = SessionTitleGenerator { _, _, _ -> error("optional title failure") },
            settings = SessionTitleSettings()) { _, index, rpc ->
            assertEquals(1, rpc.appendUserMessage(index, listOf(ContentItem.InputText("persist"))))
            assertEquals(AgentStateValue.UserMessage, rpc.getState(index))
        }
    }
}

private suspend fun <T : Any> CoroutineScope.checkTimeline(
    index: Int, rpc: TimelineRpc<T>, storage: CachedIndexVersioned<T>,
) {
    val nonce = rpc.getCacheNonce(index)
    assertEquals(storage.cacheNonce.value, nonce)
    assertEquals(storage.latestIndex.value, rpc.getLatestIndex(index))
    assertEquals(storage.indexesIn(0..100), rpc.indexesIn(index, nonce, 0, 100))
    assertEquals(storage.valuesIn(0..100), rpc.valuesIn(index, nonce, 0, 100))
    assertEquals(emptyList(), rpc.valuesIn(index, nonce, 4, 3))
    assertEquals(storage.floorToIndex(100), rpc.floorToIndex(index, nonce, 100))
    assertEquals(storage.ceilToIndex(0), rpc.ceilToIndex(index, nonce, 0))
    assertNull(rpc.getExact(index, nonce, 100))
    assertFailsWith<CacheNonceMismatch> { rpc.getExact(index, nonce xor 1, 0) }
    val job = Job(coroutineContext[Job])
    try {
        val cached = CoroutineScope(coroutineContext + job).rpcCachedIndexVersioned(index, rpc)
        for ((position, value) in storage.valuesIn(0..100)) {
            assertEquals(value, cached.getExact(position))
            assertEquals(value, cached[position])
        }
    } finally { job.cancelAndJoin() }
}

internal suspend fun runtimeFixture(
    client: OpenAiClient = mockOpenAiClient(),
    generator: SessionTitleGenerator = SessionTitleGenerator { _, _, _ -> error("Titles disabled in fixture") },
    settings: SessionTitleSettings = SessionTitleSettings(enabled = false),
    block: suspend CoroutineScope.(BackendSessionHost, Int, BackendAgentRuntimeRpc) -> Unit,
) = withTimeout(30.seconds) {
    val root = Path(SystemTemporaryDirectory, "kodex-rpc-runtime-${Random.nextLong()}")
    try {
        withBackendSessionHost(TestTimeSource(), {
            FileSystemKodexSessionRepository(root, testKodexAgentDependencies(client))
        }) { host ->
            val index = host.repository().create()
            host.keepSessionAlive(index)
            host.inSession(index) {
                runtime.modify { it.initialize(KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = "Session $index")) }
            }
            block(host, index, BackendAgentRuntimeRpc(host, { settings }, generator))
        }
    } finally {
        suspend fun delete(path: Path) {
            val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
            if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { delete(it) }
            SystemCoroutineFileSystem.delete(path)
        }
        delete(root)
    }
}
