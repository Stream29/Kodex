@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.reactivecircus.cache4k.FakeTimeSource
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.rpc.contract.TimelineRpc
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val rpcCachedIndexVersionedTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("metadata is shared and latestIndex reads never poll the initial Get") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            assertEquals(17L, view.cacheNonce.value)
            assertEquals(5, view.latestIndex())
            runCurrent()
            val observerA = launch { view.latestIndex.collect() }
            val observerB = launch { view.latestIndex.collect() }
            runCurrent()
            observerA.cancelAndJoin()
            observerB.cancelAndJoin()
            backend.latest.value = 9
            runCurrent()
            assertEquals(9, view.latestIndex())
            assertEquals(9, view.latestIndex())
            assertEquals(1, backend.count("nonce"))
            assertEquals(1, backend.count("latest"))
            assertEquals(2, backend.subscriptions.value)
        }
    }

    test("all six sparse queries preserve their meanings and inclusive ranges") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            assertEquals("two", view[4])
            assertEquals(null, view.getExact(4))
            assertEquals(2, view.floorToIndex(4))
            assertEquals(5, view.ceilToIndex(4))
            assertEquals(null, view.floorToIndex(-1))
            assertEquals(null, view.ceilToIndex(6))
            assertEquals(listOf(2, 5), view.indexesIn(1..5))
            assertEquals(listOf(2 to "two", 5 to "five"), view.valuesIn(1..5))
            assertEquals(emptyList(), view.indexesIn(5..4))
            assertEquals(emptyList(), view.valuesIn(5..4))
            assertFailsWith<IllegalArgumentException> { view[-1] }
            assertEquals("zero", view.getExact(0))
        }
    }

    test("existing exact values are cached but absent entries remain queryable after an append") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            assertEquals("five", view.getExact(5))
            assertEquals("five", view.getExact(5))
            assertEquals(1, backend.count("exact"))
            assertEquals(null, view.getExact(9))
            assertEquals(null, view.getExact(9))
            assertEquals(3, backend.count("exact"))
            backend.entries.value += 9 to "nine"
            backend.latest.value = 9
            runCurrent()
            assertEquals("nine", view.getExact(9))
            assertEquals("five", view.getExact(5))
            assertEquals(4, backend.count("exact"))
            assertEquals(17L, view.cacheNonce.value)
        }
    }

    test("visible values are not cached under the requested index and future queries see appends") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            assertEquals("five", view[10])
            assertEquals(null, view.getExact(10))
            assertEquals(5, view.floorToIndex(10))
            assertEquals(null, view.ceilToIndex(6))
            assertEquals(emptyList(), view.indexesIn(6..10))
            assertEquals(emptyList(), view.valuesIn(6..10))
            backend.entries.value += 8 to "eight"
            backend.latest.value = 8
            runCurrent()
            assertEquals("eight", view[10])
            assertEquals(8, view.floorToIndex(10))
            assertEquals(8, view.ceilToIndex(6))
            assertEquals(listOf(8), view.indexesIn(6..10))
            assertEquals(listOf(8 to "eight"), view.valuesIn(6..10))
            assertEquals(2, backend.count("get"))
            assertEquals(2, backend.count("values"))
        }
    }

    test("range values populate exact cache but do not cache the range query itself") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            repeat(2) { assertEquals(listOf(2 to "two", 5 to "five"), view.valuesIn(2..5)) }
            assertEquals("two", view.getExact(2))
            assertEquals("five", view.getExact(5))
            assertEquals(0, backend.count("exact"))
            assertEquals(2, backend.count("values"))
        }
    }

    test("new nonce invalidates populated values even when the tail stays the same") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            assertEquals("zero", view.getExact(0))
            backend.entries.value += 0 to "replacement"
            backend.nonce.value = 18
            runCurrent()
            assertEquals(5, view.latestIndex())
            assertEquals(18L, view.cacheNonce.value)
            assertEquals("replacement", view.getExact(0))
            assertEquals(2, backend.count("exact"))
        }
    }

    for (method in listOf("exact", "values", "get", "floor", "ceil", "indexes")) {
        test("late $method result is rejected without repopulating the new nonce cache") {
            runTest {
                val backend = TimelineProbeImpl()
                val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                backend.beforeReturn = {
                    if (it == method) {
                        started.complete(Unit)
                        finish.await()
                    }
                }
                supervisorScope {
                    val oldRead = async { view.readUsing(method) }
                    started.await()
                    backend.entries.value += 0 to "new"
                    backend.nonce.value = 18
                    runCurrent()
                    backend.beforeReturn = {}
                    assertEquals("new", view.getExact(0))
                    finish.complete(Unit)
                    assertFailsWith<CacheNonceMismatch> { oldRead.await() }
                    val reads = backend.count("exact")
                    assertEquals("new", view.getExact(0))
                    assertEquals(reads, backend.count("exact"))
                }
            }
        }
    }

    test("a nonce mismatch is not retried or used to refetch metadata") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            runCurrent()
            backend.nonce.value = 18
            // The remote value has changed, but the frontend collector has not run yet.
            assertFailsWith<CacheNonceMismatch> { view.getExact(0) }
            assertEquals(1, backend.count("exact"))
            assertEquals(1, backend.count("nonce"))
            runCurrent()
            assertEquals("zero", view.getExact(0))
            assertEquals(2, backend.count("exact"))
        }
    }

    test("size eviction and access expiry do not replace the backend nonce") {
        runTest {
            val backend = TimelineProbeImpl()
            val clock = FakeTimeSource()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend, 1, clock)
            assertEquals("zero", view.getExact(0))
            clock += 50.seconds
            assertEquals("zero", view.getExact(0))
            clock += 50.seconds
            assertEquals("zero", view.getExact(0))
            assertEquals(1, backend.count("exact"))
            assertEquals("two", view.getExact(2))
            assertEquals("zero", view.getExact(0))
            assertEquals(3, backend.count("exact"))
            clock += 61.seconds
            assertEquals("zero", view.getExact(0))
            assertEquals(4, backend.count("exact"))
            assertEquals(17L, view.cacheNonce.value)
        }
    }

    for (method in listOf("nonce", "latest")) {
        test("initial $method Get failure releases partial subscriptions and exposes no handle") {
            runTest {
                val backend = TimelineProbeImpl()
                backend.beforeGet = { if (it == method) throw IllegalArgumentException("failed Get") }
                assertFailsWith<IllegalArgumentException> {
                    backgroundScope.rpcCachedIndexVersioned(7, backend)
                }
                runCurrent()
                assertEquals(0, backend.subscriptions.value)
                assertEquals(0, backend.count("exact"))
            }
        }
    }

    test("cancelling initialization releases the first subscription and outstanding Get") {
        runTest {
            val backend = TimelineProbeImpl()
            val started = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            backend.beforeGet = {
                if (it == "latest") {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        released.complete(Unit)
                    }
                }
            }
            val initialization = launch { backgroundScope.rpcCachedIndexVersioned(7, backend) }
            started.await()
            runCurrent()
            initialization.cancelAndJoin()
            released.await()
            assertEquals(0, backend.subscriptions.value)
        }
    }

    test("immediate second Flow creation failure releases the first subscription") {
        runTest {
            val backend = TimelineProbeImpl()
            val rpc = object : TimelineRpc<String> by backend {
                override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> =
                    throw IllegalArgumentException("cannot create Flow")
            }
            assertFailsWith<IllegalArgumentException> {
                backgroundScope.rpcCachedIndexVersioned(7, rpc)
            }
            runCurrent()
            assertEquals(0, backend.subscriptions.value)
        }
    }

    test("owner cancellation during initialization also cancels the outstanding Get caller") {
        runTest {
            val backend = TimelineProbeImpl()
            val ownerJob = Job(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
            val started = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            backend.beforeGet = {
                if (it == "latest") {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        released.complete(Unit)
                    }
                }
            }
            val initialization = async { owner.rpcCachedIndexVersioned(7, backend) }
            started.await()
            ownerJob.cancelAndJoin()
            assertFailsWith<CancellationException> { initialization.await() }
            released.await()
            assertEquals(0, backend.subscriptions.value)
        }
    }

    test("ordinary query failure preserves the binding and does not replace cached entries") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            assertEquals("zero", view.getExact(0))
            backend.beforeReturn = { throw IllegalArgumentException("query failed") }
            assertFailsWith<IllegalArgumentException> { view.getExact(2) }
            backend.beforeReturn = {}
            assertEquals("zero", view.getExact(0))
            assertEquals("two", view.getExact(2))
            assertEquals(3, backend.count("exact"))
            runCurrent()
            assertEquals(2, backend.subscriptions.value)
        }
    }

    test("cancelled metadata stops the entire binding even under a supervisor owner") {
        runTest {
            val backend = TimelineProbeImpl()
            val ownerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            try {
                val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
                val view = owner.rpcCachedIndexVersioned(7, backend)
                assertEquals("zero", view.getExact(0))
                backend.metadataFailure.value = CancellationException("remote collection cancelled")
                runCurrent()
                assertFailsWith<CancellationException> { view.getExact(0) }
                assertEquals(0, backend.subscriptions.value)
                assertTrue(ownerJob.isActive)
            } finally {
                ownerJob.cancelAndJoin()
            }
        }
    }

    test("completed metadata does not leave a usable view with a permanently frozen nonce") {
        runTest {
            val backend = TimelineProbeImpl()
            val rpc = object : TimelineRpc<String> by backend {
                override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long> = flowOf(17L)
            }
            val view = backgroundScope.rpcCachedIndexVersioned(7, rpc)
            runCurrent()
            assertFailsWith<CancellationException> { view.latestIndex() }
            assertEquals(0, backend.subscriptions.value)
        }
    }

    test("query cancellation does not close the view or retry the query") {
        runTest {
            val backend = TimelineProbeImpl()
            val view = backgroundScope.rpcCachedIndexVersioned(7, backend)
            val started = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            backend.beforeReturn = {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            val read = launch { view.getExact(0) }
            started.await()
            read.cancelAndJoin()
            released.await()
            backend.beforeReturn = {}
            assertEquals("zero", view.getExact(0))
            assertEquals(2, backend.count("exact"))
        }
    }

    test("owner cancellation rejects cache hits and cancels reads started by another scope") {
        runTest {
            val backend = TimelineProbeImpl()
            val ownerJob = Job(backgroundScope.coroutineContext[Job])
            val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
            val view = owner.rpcCachedIndexVersioned(7, backend)
            assertEquals("zero", view.getExact(0))
            val started = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            backend.beforeReturn = {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            val read = async { view.getExact(2) }
            started.await()
            ownerJob.cancelAndJoin()
            assertFailsWith<CancellationException> { read.await() }
            released.await()
            assertEquals(0, backend.subscriptions.value)
            assertFailsWith<CancellationException> { view.getExact(0) }
            assertFailsWith<CancellationException> { view.latestIndex() }
        }
    }

    test("SessionNotActive from a query closes only the binding and never serves its cached value") {
        runTest {
            val backend = TimelineProbeImpl()
            val ownerJob = SupervisorJob(backgroundScope.coroutineContext[Job])
            try {
                val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
                val view = owner.rpcCachedIndexVersioned(7, backend)
                assertEquals("zero", view.getExact(0))
                backend.beforeReturn = { throw SessionNotActive() }
                assertFailsWith<SessionNotActive> { view.getExact(2) }
                runCurrent()
                assertFailsWith<CancellationException> { view.getExact(0) }
                assertTrue(ownerJob.isActive)
                assertEquals(0, backend.subscriptions.value)
            } finally {
                ownerJob.cancelAndJoin()
            }
        }
    }

    for (failure in listOf(SessionNotActive(), IllegalStateException("metadata failed"))) {
        test("metadata ${failure::class.simpleName} reaches its owner and invalidates the view") {
            runTest {
                val backend = TimelineProbeImpl()
                supervisorScope {
                    val ready = CompletableDeferred<CachedIndexVersioned<String>>()
                    val owner = async {
                        coroutineScope {
                            ready.complete(rpcCachedIndexVersioned(7, backend))
                            awaitCancellation()
                        }
                    }
                    val view = ready.await()
                    assertEquals("zero", view.getExact(0))
                    backend.metadataFailure.value = failure
                    val actual = assertFailsWith<Throwable> { owner.await() }
                    assertEquals(failure::class, actual::class)
                    assertEquals(0, backend.subscriptions.value)
                    assertFailsWith<CancellationException> { view.getExact(0) }
                }
            }
        }
    }

    test("real RPC invalidation rejects a late exact read and cancellation releases both subscriptions") {
        withTimelineProbe { rpc, backend ->
            val ownerJob = Job(coroutineContext[Job])
            val owner = CoroutineScope(coroutineContext + ownerJob)
            try {
                val view = owner.rpcCachedIndexVersioned(7, rpc)
                backend.subscriptions.first { it == 2 }
                assertEquals("zero", view.getExact(0))
                assertEquals("zero", view.getExact(0))
                assertEquals(1, backend.count("exact"))
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                backend.beforeReturn = {
                    if (it == "exact") { started.complete(Unit); finish.await() }
                }
                supervisorScope {
                    val old = async { view.getExact(2) }
                    started.await()
                    backend.entries.value += 2 to "new"
                    backend.nonce.value = 18
                    view.cacheNonce.first { it == 18L }
                    finish.complete(Unit)
                    assertFailsWith<CacheNonceMismatch> { old.await() }
                    backend.beforeReturn = {}
                    assertEquals("new", view.getExact(2))
                }
                ownerJob.cancelAndJoin()
                backend.subscriptions.first { it == 0 }
                assertFailsWith<CancellationException> { view.getExact(0) }
                assertEquals("new", rpc.getExact(7, 18, 2))
            } finally {
                ownerJob.cancelAndJoin()
            }
        }
    }

    test("real RPC SessionNotActive fails the binding owner without closing the shared connection") {
        withTimelineProbe { rpc, backend ->
            supervisorScope {
                val ready = CompletableDeferred<CachedIndexVersioned<String>>()
                val owner = async {
                    coroutineScope {
                        ready.complete(rpcCachedIndexVersioned(7, rpc))
                        awaitCancellation()
                    }
                }
                val view = ready.await()
                backend.subscriptions.first { it == 2 }
                assertEquals("zero", view.getExact(0))
                backend.active.value = false
                assertFailsWith<SessionNotActive> { owner.await() }
                backend.subscriptions.first { it == 0 }
                assertFailsWith<CancellationException> { view.getExact(0) }
                backend.active.value = true
                assertEquals(17L, rpc.getCacheNonce(7))
                assertEquals(0, backend.subscriptions.value)
            }
        }
    }
}

private suspend fun CachedIndexVersioned<String>.readUsing(method: String): Any? = when (method) {
    "exact" -> getExact(0)
    "values" -> valuesIn(0..5)
    "get" -> get(1)
    "floor" -> floorToIndex(1)
    "ceil" -> ceilToIndex(1)
    "indexes" -> indexesIn(0..5)
    else -> error("Unexpected method")
}
