@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.backend

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

val backendLifecycleTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("starting is not ready and startup time does not consume the ready idle interval") {
        runTest {
            val clock = TestTimeSource()
            val lifecycle = createBackendLifecycle(timeSource = clock)
            clock += 20.minutes
            assertFalse(lifecycle.renew())
            assertFalse(lifecycle.expireIfIdle())
            assertTrue(lifecycle.markReady())
            assertFalse(lifecycle.markReady())
            clock += 5.minutes - 1.seconds
            assertFalse(lifecycle.expireIfIdle())
            clock += 1.seconds
            assertTrue(lifecycle.expireIfIdle())
            assertEquals(BackendStopReason.IdleTimeout, lifecycle.awaitStop())
        }
    }

    test("each successful renewal grants a full interval and process existence is not a renewal") {
        runTest {
            val clock = TestTimeSource()
            val lifecycle = createBackendLifecycle(timeSource = clock)
            lifecycle.markReady()
            clock += 4.minutes
            assertTrue(lifecycle.renew())
            clock += 4.minutes
            assertFalse(lifecycle.expireIfIdle())
            clock += 1.minutes
            assertTrue(lifecycle.expireIfIdle())
            assertFalse(lifecycle.renew())
        }
    }

    test("manual stop is accepted without finishing cleanup and cannot be renewed away") {
        runTest {
            val lifecycle = createBackendLifecycle()
            lifecycle.markReady()
            assertEquals(BackendStopAcceptance.Accepted, lifecycle.requestStop())
            assertEquals(BackendPhase.Stopping, lifecycle.state.value)
            assertEquals(BackendStopReason.Manual, lifecycle.awaitStop())
            assertFalse(lifecycle.renew())
            assertFalse(lifecycle.markReady())
            assertFalse(lifecycle.expireIfIdle())
            lifecycle.updateIdleTimeout(Duration.INFINITE)
            assertEquals(BackendStopAcceptance.AlreadyStopping, lifecycle.requestStop())
            assertTrue(lifecycle.finishStop())
            assertEquals(BackendPhase.Stopped, lifecycle.state.value)
            assertEquals(BackendStopReason.Manual, lifecycle.awaitStop())
            assertFalse(lifecycle.finishStop())
            assertEquals(BackendStopAcceptance.AlreadyStopped, lifecycle.requestStop())
        }
    }

    test("manual stop can interrupt startup and prevents a late ready publication") {
        runTest {
            val lifecycle = createBackendLifecycle()
            assertEquals(BackendStopAcceptance.Accepted, lifecycle.requestStop())
            assertFalse(lifecycle.markReady())
            assertFalse(lifecycle.renew())
            assertTrue(lifecycle.finishStop())
        }
    }

    test("a cancelled stop waiter does not stop the backend or retract an accepted stop") {
        runTest {
            val lifecycle = createBackendLifecycle()
            lifecycle.markReady()
            val waiter = async { lifecycle.awaitStop() }
            runCurrent()
            waiter.cancelAndJoin()
            assertEquals(BackendPhase.Ready, lifecycle.state.value)
            lifecycle.requestStop()
            assertEquals(BackendStopReason.Manual, lifecycle.awaitStop())
            assertEquals(BackendPhase.Stopping, lifecycle.state.value)
        }
    }

    test("an already cancelled caller cannot publish readiness accept stop or renew") {
        runTest {
            val clock = TestTimeSource()
            val lifecycle = createBackendLifecycle(timeSource = clock)
            launch {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { lifecycle.markReady() }
                assertFailsWith<CancellationException> { lifecycle.requestStop() }
            }.join()
            assertEquals(BackendPhase.Starting, lifecycle.state.value)
            assertTrue(lifecycle.markReady())
            clock += 5.minutes
            launch {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { lifecycle.renew() }
                assertFailsWith<CancellationException> { lifecycle.updateIdleTimeout(Duration.INFINITE) }
                assertFailsWith<CancellationException> { lifecycle.expireIfIdle() }
            }.join()
            assertEquals(BackendPhase.Ready, lifecycle.state.value)
            assertTrue(lifecycle.expireIfIdle())
            launch {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> { lifecycle.finishStop() }
                assertFailsWith<CancellationException> { lifecycle.awaitStop() }
            }.join()
            assertEquals(BackendPhase.Stopping, lifecycle.state.value)
            assertTrue(lifecycle.finishStop())
        }
    }

    test("concurrent manual requests have exactly one acceptance") {
        runTest {
            val lifecycle = createBackendLifecycle()
            lifecycle.markReady()
            val results = List(64) { async(Dispatchers.Default) { lifecycle.requestStop() } }.awaitAll()
            assertEquals(1, results.count { it == BackendStopAcceptance.Accepted })
            assertEquals(63, results.count { it == BackendStopAcceptance.AlreadyStopping })
            assertEquals(BackendStopReason.Manual, lifecycle.awaitStop())
        }
    }

    test("idle and manual stop races retain exactly one first reason") {
        runTest {
            repeat(40) {
                val clock = TestTimeSource()
                val lifecycle = createBackendLifecycle(1.seconds, clock)
                lifecycle.markReady()
                clock += 1.seconds
                val outcomes = List(16) { index ->
                    async(Dispatchers.Default) {
                        if (index % 2 == 0) lifecycle.expireIfIdle()
                        else lifecycle.requestStop() == BackendStopAcceptance.Accepted
                    }
                }.awaitAll()
                assertEquals(1, outcomes.count { it })
                val reason = lifecycle.awaitStop()
                assertEquals(BackendPhase.Stopping, lifecycle.state.value)
                assertFalse(lifecycle.renew())
                assertTrue(lifecycle.finishStop())
                assertEquals(reason, lifecycle.awaitStop())
            }
        }
    }

    test("renewal and expiry race cannot renew a backend that already accepted expiry") {
        runTest {
            repeat(40) {
                val clock = TestTimeSource()
                val lifecycle = createBackendLifecycle(1.seconds, clock)
                lifecycle.markReady()
                clock += 1.seconds
                val renewal = async(Dispatchers.Default) { lifecycle.renew() }
                val expiry = async(Dispatchers.Default) { lifecycle.expireIfIdle() }
                val renewed = renewal.await()
                val expired = expiry.await()
                assertTrue(renewed xor expired)
                if (expired) {
                    assertEquals(BackendPhase.Stopping, lifecycle.state.value)
                    assertEquals(BackendStopReason.IdleTimeout, lifecycle.awaitStop())
                    assertFalse(lifecycle.renew())
                } else {
                    assertEquals(BackendPhase.Ready, lifecycle.state.value)
                    assertFalse(lifecycle.expireIfIdle())
                }
            }
        }
    }

    test("infinite idle TTL disables only idle expiry not manual stop") {
        runTest {
            val clock = TestTimeSource()
            val lifecycle = createBackendLifecycle(Duration.INFINITE, clock)
            lifecycle.markReady()
            clock += 1_000_000.minutes
            assertFalse(lifecycle.expireIfIdle())
            assertTrue(lifecycle.renew())
            assertEquals(BackendStopAcceptance.Accepted, lifecycle.requestStop())
            assertEquals(BackendStopReason.Manual, lifecycle.awaitStop())
        }
    }

    test("changing TTL never creates an implicit renewal") {
        runTest {
            val clock = TestTimeSource()
            val lifecycle = createBackendLifecycle(timeSource = clock)
            lifecycle.markReady()
            clock += 4.minutes
            lifecycle.updateIdleTimeout(Duration.INFINITE)
            clock += 10.minutes
            assertFalse(lifecycle.expireIfIdle())
            lifecycle.updateIdleTimeout(5.minutes)
            assertTrue(lifecycle.expireIfIdle())
        }
    }

    test("invalid TTL and invalid premature cleanup are rejected") {
        runTest {
            assertFailsWith<IllegalArgumentException> { createBackendLifecycle(Duration.ZERO) }
            assertFailsWith<IllegalArgumentException> { createBackendLifecycle((-1).seconds) }
            assertFailsWith<IllegalArgumentException> { createBackendLifecycle(-Duration.INFINITE) }
            val lifecycle = createBackendLifecycle()
            assertFailsWith<IllegalStateException> { lifecycle.finishStop() }
            lifecycle.markReady()
            assertFailsWith<IllegalStateException> { lifecycle.finishStop() }
            assertFailsWith<IllegalArgumentException> { lifecycle.updateIdleTimeout(Duration.ZERO) }
            assertEquals(BackendPhase.Ready, lifecycle.state.value)
        }
    }

    test("host owned expiry observer accepts idle stop at the deadline") {
        runTest {
            val lifecycle = createBackendLifecycle(10.seconds, testScheduler.timeSource)
            lifecycle.markReady()
            val monitor = launch { lifecycle.monitorIdleExpiry() }
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(BackendPhase.Ready, lifecycle.state.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(BackendStopReason.IdleTimeout, lifecycle.awaitStop())
            monitor.join()
            assertEquals(BackendPhase.Stopping, lifecycle.state.value)
        }
    }

    test("cancelling the expiry observer does not imply cancelling backend work") {
        runTest {
            val lifecycle = createBackendLifecycle(timeSource = testScheduler.timeSource)
            lifecycle.markReady()
            val monitor = launch { lifecycle.monitorIdleExpiry() }
            runCurrent()
            monitor.cancelAndJoin()
            assertEquals(BackendPhase.Ready, lifecycle.state.value)
            assertTrue(lifecycle.renew())
            assertEquals(BackendStopAcceptance.Accepted, lifecycle.requestStop())
        }
    }

    test("expiry observer exits after manual stop and rejects invalid polling intervals") {
        runTest {
            val lifecycle = createBackendLifecycle()
            assertFailsWith<IllegalArgumentException> { lifecycle.monitorIdleExpiry(Duration.ZERO) }
            assertFailsWith<IllegalArgumentException> { lifecycle.monitorIdleExpiry(Duration.INFINITE) }
            lifecycle.requestStop()
            lifecycle.monitorIdleExpiry()
            assertEquals(BackendStopReason.Manual, lifecycle.awaitStop())
        }
    }

    test("phase observation and the stop signal retain accepted shutdown") {
        runTest {
            val lifecycle = createBackendLifecycle()
            val phases = mutableListOf<BackendPhase>()
            val observer = launch { lifecycle.state.take(3).toList(phases) }
            runCurrent()
            lifecycle.markReady()
            runCurrent()
            lifecycle.requestStop()
            runCurrent()
            observer.join()
            assertEquals(listOf(BackendPhase.Starting, BackendPhase.Ready, BackendPhase.Stopping), phases)
            assertEquals(BackendStopReason.Manual, lifecycle.awaitStop())
        }
    }
}
