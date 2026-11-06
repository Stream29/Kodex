package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerationResult
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.client.rpcCachedAgentStorage
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
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds

val backendRuntimeRpcTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("six concrete services retain real sparse values and frontend cache semantics") {
        runtimeFixture { host, index, _ ->
            withInMemoryRpc(registerServices = {
                registerService(AgentStorageRpc::class) { BackendAgentStorageRpc(host) }
            }) { raw ->
                val client = RestoringRpcClient(raw)
                val runtime = host.session(index).session.runtime
                runtime.appendUserMessage(listOf(ContentItem.InputText("one")))
                runtime.modify {
                    it.commit(
                        listOf(
                            StableReasoning(ResponseItem.Reasoning(encryptedContent = "test-reasoning")),
                            UnstableSnapshot(emptyList()),
                            TokenCountSnapshot.Response(
                                0, TokenUsage(0, 0, 0),
                                TokenCountDiagnostics(requestId = "test", turnStateReceived = true),
                            ),
                        ),
                    )
                }
                val storage = runtime.storage as ObservableKodexAgentStorage
                val rpc = client.withService<AgentStorageRpc>()
                checkTimeline(index, rpc, storage, AgentCommitType.IndexTypes) { it as CleanIndexEntry }
                checkTimeline(index, rpc, storage, AgentCommitType.WorkTypes) { it as StableWorkEvent }
                checkTimeline(index, rpc, storage, AgentCommitType.SettingsTypes) { it as KodexAgentSettings }
                checkTimeline(index, rpc, storage, AgentCommitType.TimestampTypes) { (it as CommitTimestamp).instant }
                checkTimeline(index, rpc, storage, AgentCommitType.TokenCountTypes) { it as TokenCountSnapshot }
                checkTimeline(index, rpc, storage, AgentCommitType.UnstableTypes) { (it as UnstableSnapshot).events }
                val nonce = rpc.getCacheNonce(index)
                assertEquals(
                    emptyList(),
                    (rpc.getCommitsExact(index, nonce, runtime.latestIndex.value, AgentCommitType.UnstableTypes).singleOrNull() as? UnstableSnapshot)?.events,
                )
                assertEquals(emptyList(), rpc.getCommitsExact(index, nonce, 999, AgentCommitType.UnstableTypes))
            }
        }
    }

    test("settings CAS uses actual current value and equal writes leave the tail unchanged") {
        runtimeFixture { host, index, _ ->
            val rpc = BackendAgentStorageRpc(host)
            val nonce = rpc.getCacheNonce(index)
            val old = rpc.getLastCommitsIn(index, nonce, 0, rpc.getLatestIndex(index), AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings
            val tail = rpc.getLatestIndex(index)
            assertTrue(rpc.compareAndSetSettings(index, old, old))
            assertEquals(tail, rpc.getLatestIndex(index))
            assertTrue(rpc.compareAndSetSettings(index, old, old.copy(threadName = "changed")))
            assertFalse(rpc.compareAndSetSettings(index, old, old.copy(threadName = "stale")))
            assertEquals(nonce, rpc.getCacheNonce(index))
        }
    }

    test("revert checks nonce at write admission clears steer and invalidates the frontend cache") {
        runtimeFixture { host, index, rpc ->
            val timelines = BackendAgentStorageRpc(host)
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("old")))
            val nonce = timelines.getCacheNonce(index)
            assertTrue(rpc.compareAndSetPendingSteer(index, emptyList(), listOf(StableUserMessage(listOf(ContentItem.InputText("queued"))))))
            assertFailsWith<CacheNonceMismatch> { rpc.revertHistory(index, 1, nonce xor 1) }
            assertFailsWith<IllegalArgumentException> { rpc.revertHistory(index, 0, nonce) }
            rpc.revertHistory(index, 1, nonce)
            assertEquals(0, rpc.getLatestIndex(index))
            assertEquals(emptyList(), rpc.getPendingSteer(index))
            assertNotEquals(nonce, timelines.getCacheNonce(index))
            assertFailsWith<CacheNonceMismatch> { timelines.getCommitsExact(index, nonce, 1, AgentCommitType.IndexTypes) }
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("replacement")))
            assertEquals(
                "replacement",
                (timelines.getCommitsExact(index, timelines.getCacheNonce(index), 1, AgentCommitType.IndexTypes).single() as StableUserMessage)
                    .content.filterIsInstance<ContentItem.InputText>().single().text,
            )
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
                registerService(AgentStorageRpc::class) { BackendAgentStorageRpc(host) }
            }) { raw ->
                val restored = RestoringRpcClient(raw)
                val runtime = restored.withService<AgentRuntimeRpc>()
                val settings = restored.withService<AgentStorageRpc>()
                runtime.appendUserMessage(index, listOf(ContentItem.InputText("compact")))
                val before = settings.getLastCommitsIn(
                    index, settings.getCacheNonce(index), 0, settings.getLatestIndex(index), AgentCommitType.SettingsTypes, 1,
                ).single().commit as KodexAgentSettings
                val operation = async { runtime.forcedCompact(index) }
                try {
                    entered.await()
                    val first = before.copy(threadName = "accepted-during-compaction")
                    assertTrue(settings.compareAndSetSettings(index, before, first))
                    val latest = first.copy(model = OpenAiModelId("updated-model"))
                    assertTrue(settings.compareAndSetSettings(index, first, latest))
                    release.complete(Unit)
                    val checkpoint = operation.await()
                    val published = settings.getLastCommitsIn(
                        index, settings.getCacheNonce(index), 0, checkpoint, AgentCommitType.SettingsTypes, 1,
                    ).single().commit as KodexAgentSettings
                    assertEquals(latest.threadName, published.threadName)
                    assertEquals(latest.model, published.model)
                    // The returned index is the compaction output; settings
                    // belong to its immediately preceding compaction point.
                    assertEquals(
                        checkpoint - 1,
                        settings.getLastCommitsIn(
                            index, settings.getCacheNonce(index), 0, checkpoint, AgentCommitType.SettingsTypes, 1,
                        ).single().index,
                    )
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
            val settings = BackendAgentStorageRpc(host)
            val nonce = settings.getCacheNonce(index)
            val old = settings.getLastCommitsIn(index, nonce, 0, settings.getLatestIndex(index), AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings
            assertTrue(settings.compareAndSetSettings(index, old, old.copy(threadName = "manual")))
            release.complete(Unit)
            titleJob.await().join()
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("second")))
            assertEquals(
                "manual",
                (settings.getLastCommitsIn(index, nonce, 0, settings.getLatestIndex(index), AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings).threadName,
            )
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
            val settings = BackendAgentStorageRpc(host)
            val nonce = settings.getCacheNonce(index)
            val old = settings.getLastCommitsIn(index, nonce, 0, settings.getLatestIndex(index), AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings
            val changed = old.copy(instructions = "new instructions")
            assertTrue(settings.compareAndSetSettings(index, old, changed))
            release.complete(Unit)
            settings.getLatestIndexFlow(index).first {
                (settings.getLastCommitsIn(index, nonce, 0, it, AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings).threadName == "automatic"
            }
            assertEquals(
                changed.instructions,
                (settings.getLastCommitsIn(index, nonce, 0, settings.getLatestIndex(index), AgentCommitType.SettingsTypes, 1).single().commit as KodexAgentSettings).instructions,
            )
        }
        runtimeFixture(generator = SessionTitleGenerator { _, _, _ -> error("optional title failure") },
            settings = SessionTitleSettings()) { _, index, rpc ->
            assertEquals(1, rpc.appendUserMessage(index, listOf(ContentItem.InputText("persist"))))
            assertEquals(AgentStateValue.UserMessage, rpc.getState(index))
        }
    }
}

private suspend fun <T : Any> CoroutineScope.checkTimeline(
    index: Int,
    rpc: AgentStorageRpc,
    storage: ObservableKodexAgentStorage,
    types: Set<AgentCommitType>,
    extract: (AgentCommit) -> T,
) {
    val nonce = rpc.getCacheNonce(index)
    val expectedValues = storage.getCommitsIn(0..100, types).map { it.index to extract(it.commit) }
    val expectedLatest = expectedValues.lastOrNull()?.first ?: -1
    assertEquals(storage.cacheNonce.value, nonce)
    assertEquals(storage.latestIndex(), rpc.getLatestIndex(index))
    assertEquals(expectedValues.map { it.first }, rpc.indexesIn(index, nonce, 0, 100, types))
    assertEquals(expectedValues, rpc.getCommitsIn(index, nonce, 0, 100, types).map { it.index to extract(it.commit) })
    assertEquals(emptyList(), rpc.getCommitsIn(index, nonce, 4, 3, types))
    assertEquals(expectedLatest.takeIf { it >= 0 }, rpc.getLastCommitsIn(index, nonce, 0, 100, types, 1).lastOrNull()?.index)
    assertEquals(expectedValues.firstOrNull()?.first, rpc.getFirstCommitsIn(index, nonce, 0, 100, types, 1).firstOrNull()?.index)
    assertEquals(emptyList(), rpc.getCommitsExact(index, nonce, 100, types))
    assertFailsWith<CacheNonceMismatch> { rpc.getCommitsExact(index, nonce xor 1, 0, types) }
    val job = Job(coroutineContext[Job])
    try {
        val cached = CoroutineScope(coroutineContext + job).rpcCachedAgentStorage(index, storage.uri, rpc)
        for ((position, value) in expectedValues) {
            assertEquals(value, extract(cached.getCommitsExact(position, types).single()))
            assertEquals(value, extract(cached.getLastCommitsIn(0..position, types, 1).single().commit))
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
            SqliteKodexSessionRepository(root, testKodexAgentDependencies(client))
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
