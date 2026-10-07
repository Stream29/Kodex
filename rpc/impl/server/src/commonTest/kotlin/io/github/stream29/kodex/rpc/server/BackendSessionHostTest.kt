@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds

val backendSessionHostTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("untouched host and inactive runtime reads never acquire a repository") {
        runTest {
            var acquisitions = 0
            lateinit var closedHost: BackendSessionHost
            withBackendSessionHost(testScheduler.timeSource, {
                acquisitions++
                InMemoryKodexSessionRepository(testKodexAgentDependencies())
            }) { host ->
                closedHost = host
                assertFailsWith<SessionNotActive> { host.session(0) }
                assertEquals(0, acquisitions)
            }
            assertFailsWith<CancellationException> { closedHost.repository() }
            assertEquals(0, acquisitions)
        }
    }

    test("concurrent first access publishes exactly one actual repository under the original owner") {
        runTest {
            var acquisitions = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var repository: KodexRootSessionRepository
            withBackendSessionHost(testScheduler.timeSource, {
                acquisitions++
                entered.complete(Unit)
                release.await()
                InMemoryKodexSessionRepository(testKodexAgentDependencies()).also { repository = it }
            }) { host ->
                val waiters = (1..8).map { async { host.repository() } }
                entered.await()
                runCurrent()
                assertEquals(1, acquisitions)
                waiters.first().cancelAndJoin()
                release.complete(Unit)
                waiters.drop(1).awaitAll().forEach { assertSame(repository, it) }
                assertSame(repository, host.repository())
                assertEquals(1, acquisitions)
            }
            assertTrue(repository.coroutineContext.job.isCompleted)
        }
    }

    test("failed and cancelled factories do not publish a repository and can be retried") {
        runTest {
            var attempts = 0
            withBackendSessionHost(testScheduler.timeSource, {
                when (++attempts) {
                    1 -> error("failed acquisition")
                    2 -> throw CancellationException("cancelled acquisition")
                    else -> InMemoryKodexSessionRepository(testKodexAgentDependencies())
                }
            }) { host ->
                assertFailsWith<IllegalStateException> { host.repository() }
                assertFailsWith<CancellationException> { host.repository() }
                val repository = host.repository()
                assertSame(repository, host.repository())
                assertEquals(3, attempts)
            }
        }
    }

    test("owner cancellation during first acquisition waits for factory cleanup") {
        runTest {
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            lateinit var repository: KodexRootSessionRepository
            val hostJob = launch {
                withBackendSessionHost(testScheduler.timeSource, {
                    try {
                        repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(1)
                            cleaned.complete(Unit)
                        }
                    }
                }) { host -> host.repository() }
            }
            entered.await()
            hostJob.cancelAndJoin()
            assertTrue(cleaned.isCompleted)
            assertTrue(repository.coroutineContext.job.isCompleted)
        }
    }

    test("a factory returning an already cancelled repository cannot publish that invalid owner") {
        runTest {
            var attempts = 0
            withBackendSessionHost(testScheduler.timeSource, {
                InMemoryKodexSessionRepository(testKodexAgentDependencies()).also {
                    if (++attempts == 1) it.cancelAndJoin()
                }
            }) { host ->
                assertFailsWith<CancellationException> { host.repository() }
                val live = host.repository()
                assertTrue(live.coroutineContext.job.isActive)
                assertSame(live, host.repository())
                assertEquals(2, attempts)
            }
        }
    }

    test("only explicit keepAlive opens and concurrent activation reuses the repository owner") {
        runTest {
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                val index = host.repository().create()
                assertFalse(host.repository().getEntry(index).isActive)
                assertFailsWith<SessionNotActive> { host.session(index) }
                assertFalse(host.repository().getEntry(index).isActive)
                (1..8).map { async { host.keepSessionAlive(index) } }.awaitAll()
                val binding = host.session(index)
                assertSame(binding.session, host.repository().open(index))
                assertTrue(host.repository().getEntry(index).isActive)
                assertFalse(host.repository().getEntry(index).running)
                assertFailsWith<SessionNotFound> { host.keepSessionAlive(index + 1) }
                assertFalse(host.deleteSession(index + 1))
            }
        }
    }

    test("expiry ends the old binding and reactivation creates a new owner") {
        runTest {
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                val index = host.repository().create()
                host.keepSessionAlive(index)
                val old = host.session(index)
                val observer = async {
                    assertFailsWith<SessionNotActive> { old.observe(old.session.runtime.latestIndex).collect() }
                }
                runCurrent()
                advanceTimeBy(59_999)
                runCurrent()
                assertTrue(old.session.coroutineContext.job.isActive)
                advanceTimeBy(1)
                runCurrent()
                observer.await()
                assertTrue(old.inactive.isCompleted)
                assertTrue(old.session.coroutineContext.job.isCompleted)
                assertFalse(host.repository().getEntry(index).isActive)
                assertFailsWith<SessionNotActive> { host.session(index) }
                host.keepSessionAlive(index)
                assertNotSame(old.session, host.session(index).session)
                assertTrue(old.inactive.isCompleted)
            }
        }
    }

    test("ordinary reads pending steer and subscription cancellation do not renew") {
        runTest {
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                val index = host.repository().create()
                host.keepSessionAlive(index)
                val binding = host.session(index)
                val observer = launch { binding.observe(binding.session.runtime.latestIndex).collect() }
                runCurrent()
                observer.cancelAndJoin()
                assertTrue(binding.session.coroutineContext.job.isActive)
                advanceTimeBy(50_000)
                host.inSession(index) {
                    runtime.pendingSteer.value = listOf(StableUserMessage(listOf(ContentItem.InputText("queued"))))
                }
                host.session(index)
                host.repository().listEntries()
                advanceTimeBy(10_000)
                runCurrent()
                assertTrue(binding.inactive.isCompleted)
                assertTrue(binding.session.coroutineContext.job.isCompleted)
            }
        }
    }

    test("explicit renewal extends the existing deadline without replacing the binding") {
        runTest {
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                val index = host.repository().create()
                host.keepSessionAlive(index)
                val binding = host.session(index)
                advanceTimeBy(50_000)
                host.keepSessionAlive(index)
                assertSame(binding, host.session(index))
                advanceTimeBy(59_999)
                runCurrent()
                assertFalse(binding.inactive.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(binding.inactive.isCompleted)
            }
        }
    }

    test("activation grants the full TTL after loading and survives lost waiter") {
        runTest {
            val loading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withBackendSessionHost(testScheduler.timeSource, {
                val delegate = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                object : KodexRootSessionRepository by delegate {
                    override suspend fun open(entryIndex: Int): KodexAgentSession {
                        loading.complete(Unit)
                        release.await()
                        return delegate.open(entryIndex)
                    }
                }
            }) { host ->
                val index = host.repository().create()
                val waiter = async { host.keepSessionAlive(index) }
                loading.await()
                waiter.cancelAndJoin()
                advanceTimeBy(100_000)
                release.complete(Unit)
                runCurrent()
                val binding = host.session(index)
                advanceTimeBy(59_999)
                runCurrent()
                assertFalse(binding.inactive.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(binding.inactive.isCompleted)
            }
        }
    }

    test("failed activation does not strand a renewal and can be retried explicitly") {
        runTest {
            val failure = IllegalStateException("load failed")
            var fail = true
            withBackendSessionHost(testScheduler.timeSource, {
                val delegate = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                object : KodexRootSessionRepository by delegate {
                    override suspend fun open(entryIndex: Int): KodexAgentSession {
                        if (fail) throw failure
                        return delegate.open(entryIndex)
                    }
                }
            }) { host ->
                val index = host.repository().create()
                assertEquals(failure.message, assertFailsWith<IllegalStateException> { host.keepSessionAlive(index) }.message)
                assertFalse(host.repository().getEntry(index).isActive)
                assertFailsWith<SessionNotActive> { host.session(index) }
                fail = false
                host.keepSessionAlive(index)
                assertTrue(host.session(index).session.coroutineContext.job.isActive)
            }
        }
    }

    test("accepted turn outlives its waiter and renews through cancellation cleanup") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _ ->
                    flow {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                releaseCleanup.await()
                            }
                        }
                    }
                }
            }
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies(client)) }) { host ->
                val index = host.createInitialized()
                val binding = host.session(index)
                host.inSession(index) { runtime.appendUserMessage(listOf(ContentItem.InputText("run"))) }
                val waiter = async { host.inSession(index) { runtime.resume() } }
                started.await()
                val turn = binding.session.runtime.runningTurn.value!!
                waiter.cancelAndJoin()
                advanceTimeBy(130_000)
                runCurrent()
                assertTrue(turn.isActive)
                assertFalse(binding.inactive.isCompleted)
                turn.cancel()
                cleaning.await()
                advanceTimeBy(90_000)
                runCurrent()
                assertSame(turn, binding.session.runtime.runningTurn.value)
                assertFalse(binding.inactive.isCompleted)
                releaseCleanup.complete(Unit)
                runCurrent()
                assertTrue(turn.isCompleted)
                assertEquals(null, binding.session.runtime.runningTurn.value)
                advanceTimeBy(60_000)
                runCurrent()
                assertTrue(binding.inactive.isCompleted)
            }
        }
    }

    test("turn completion does not grant another TTL and ordinary work does not extend it") {
        runTest {
            val finish = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse { _ -> flow {
                    entered.complete(Unit)
                    finish.await()
                    emit(ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)))
                } }
            }
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies(client)) }) { host ->
                val index = host.createInitialized()
                val binding = host.session(index)
                host.inSession(index) { runtime.appendUserMessage(listOf(ContentItem.InputText("run"))) }
                val waiter = async { host.inSession(index) { runtime.resume() } }
                entered.await()
                advanceTimeBy(45_000)
                runCurrent() // last periodic renewal was at 40s
                host.inSession(index) { runtime.latestIndex.value }
                finish.complete(Unit)
                waiter.await()
                advanceTimeBy(54_999)
                runCurrent()
                assertFalse(binding.inactive.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(binding.inactive.isCompleted) // 40 + 60, not 45 + 60
            }
        }
    }

    test("non-running accepted work survives caller cancellation but not TTL") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                val index = host.createInitialized()
                val binding = host.session(index)
                val waiter = async {
                    host.inSession(index) {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            assertTrue(coroutineContext.job.isActive)
                            cleaned.complete(Unit)
                        }
                    }
                }
                started.await()
                waiter.cancelAndJoin()
                assertFalse(cleaned.isCompleted)
                advanceTimeBy(60_000)
                runCurrent()
                assertTrue(cleaned.isCompleted)
                assertTrue(binding.inactive.isCompleted)
            }
        }
    }

    test("manual compaction renews the same slot without a frontend waiter") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createRemoteCompactionV2Response { _ ->
                    started.complete(Unit)
                    finish.await()
                    RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "test"), null)
                }
            }
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies(client)) }) { host ->
                val index = host.createInitialized()
                host.inSession(index) { runtime.appendUserMessage(listOf(ContentItem.InputText("compact"))) }
                val binding = host.session(index)
                val waiter = async { host.inSession(index) { runtime.forcedCompact() } }
                started.await()
                waiter.cancelAndJoin()
                advanceTimeBy(120_000)
                runCurrent()
                assertTrue(host.repository().getEntry(index).running)
                assertFalse(binding.inactive.isCompleted)
                finish.complete(Unit)
                runCurrent()
                assertFalse(host.repository().getEntry(index).running)
                advanceTimeBy(60_000)
                runCurrent()
                assertTrue(binding.inactive.isCompleted)
            }
        }
    }

    test("an unconsumed shell process does not retain the Session") {
        runTest {
            backgroundScope.async {
                val clock = TestTimeSource()
                withBackendSessionHost(clock, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                    val index = host.createInitialized()
                    val binding = host.session(index)
                    val client = binding.session.runtime.unifiedExecToolClient
                    // A test-owned shell builtin waiting for stdin, with no child process.
                    val execution = async {
                        host.inSession(index) {
                            withContext(Dispatchers.Default) {
                                client.execCommand(ExecCommandArguments(command = "read ignored", yieldTimeMillis = 250))
                            }
                        }
                    }
                    client.activeSessions.first { it.isNotEmpty() }
                    assertNotNull(execution.await().sessionId)
                    assertTrue(client.activeSessions.value.isNotEmpty())
                    clock += 60.seconds
                    advanceTimeBy(60_000)
                    runCurrent()
                    binding.inactive.await()
                    assertTrue(binding.session.coroutineContext.job.isCompleted)
                    assertFalse(host.repository().getEntry(index).isActive)
                }
            }.await()
        }
    }

    test("global work retains its result semantics and isolated failure") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                val waiter = async {
                    host.inBackend {
                        started.complete(Unit)
                        finish.await()
                        completed.complete(Unit)
                        42
                    }
                }
                started.await()
                assertFalse(waiter.isCompleted)
                waiter.cancelAndJoin()
                finish.complete(Unit)
                completed.await()
                val failure = IllegalStateException("business failure")
                assertEquals(failure.message, assertFailsWith<IllegalStateException> {
                    host.inBackend { throw failure }
                }.message)
                assertEquals(42, host.inBackend { 42 })
            }
        }
    }

    test("delete waits for cleanup before same index can activate again") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withBackendSessionHost(testScheduler.timeSource, { InMemoryKodexSessionRepository(testKodexAgentDependencies()) }) { host ->
                val index = host.createInitialized()
                val old = host.session(index)
                val waiter = async {
                    assertFailsWith<CancellationException> {
                        host.inSession(index) {
                            try {
                                started.complete(Unit)
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cleanup.complete(Unit)
                                    release.await()
                                    assertTrue(coroutineContext.job.isActive)
                                    assertEquals(OpenAiModelId("test-model"), runtime.storage.settings[0].model)
                                }
                            }
                        }
                    }
                }
                started.await()
                val deletion = async { host.deleteSession(index) }
                cleanup.await()
                val activation = async {
                    assertFailsWith<SessionNotFound> { host.keepSessionAlive(index) }
                }
                runCurrent()
                assertFalse(deletion.isCompleted)
                assertFalse(activation.isCompleted)
                release.complete(Unit)
                assertTrue(deletion.await())
                activation.await()
                waiter.await()
                assertTrue(old.inactive.isCompleted)
                assertFalse(host.deleteSession(index))
                assertEquals(index, host.repository().create())
                host.keepSessionAlive(index)
                assertNotSame(old.session, host.session(index).session)
            }
        }
    }

    for (cancelOwner in listOf(false, true)) {
        test("host shutdown joins all owners cancelOwner=$cancelOwner") {
            runTest {
                lateinit var repository: KodexRootSessionRepository
                lateinit var binding: BackendSessionBinding
                val ready = CompletableDeferred<Unit>()
                val exit = CompletableDeferred<Unit>()
                val hostJob = launch {
                    withBackendSessionHost(testScheduler.timeSource, {
                        InMemoryKodexSessionRepository(testKodexAgentDependencies()).also { repository = it }
                    }) { host ->
                        val index = host.createInitialized()
                        binding = host.session(index)
                        ready.complete(Unit)
                        exit.await()
                    }
                }
                ready.await()
                if (cancelOwner) hostJob.cancel() else exit.complete(Unit)
                hostJob.join()
                assertTrue(repository.coroutineContext.job.isCompleted)
                assertTrue(binding.session.coroutineContext.job.isCompleted)
                assertTrue(binding.inactive.isCompleted)
            }
        }
    }

    test("factory failure cleans resources already created in the backend scope") {
        runTest {
            lateinit var child: Job
            val cleaned = CompletableDeferred<Unit>()
            val failure = IllegalStateException("factory failed")
            assertEquals(failure.message, assertFailsWith<IllegalStateException> {
                withBackendSessionHost(testScheduler.timeSource, {
                    child = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { cleaned.complete(Unit) }
                    }
                    throw failure
                }) { host -> host.repository() }
            }.message)
            assertTrue(child.isCompleted)
            assertTrue(cleaned.isCompleted)
        }
    }
}

private suspend fun BackendSessionHost.createInitialized(): Int {
    val index = repository().create()
    keepSessionAlive(index)
    inSession(index) { runtime.modify { it.initialize(KodexAgentSettings(model = OpenAiModelId("test-model"))) } }
    return index
}
