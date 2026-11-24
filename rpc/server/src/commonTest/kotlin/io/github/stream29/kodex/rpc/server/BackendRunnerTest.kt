@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal val backendRunnerTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("repeated transient faults back off to two seconds and one good iteration resets the interval") {
        runTest {
            val attempts = mutableListOf<Long>()
            val faults = mutableListOf<Exception>()
            val transient = IllegalStateException("temporarily unavailable")
            var stopped = false
            runBackendRunnerLoop({ stopped }, { faults += it }) {
                attempts += currentTime
                when (attempts.size) {
                    in 1..6, 8 -> throw transient
                    9 -> stopped = true
                }
            }
            assertEquals(listOf<Long>(0, 200, 600, 1_400, 3_000, 5_000, 7_000, 7_000, 7_200), attempts)
            assertEquals(List<Exception>(7) { transient }, faults)
        }
    }

    test("a runner and its async children report the first fatal Error without relying on the uncaught handler") {
        runTest {
            supervisorScope {
                val fatal = CompletableDeferred<Throwable>()
                val failure = Error("deadline child failure")
                var retries = 0
                val deadline = launchBackendRunner("await_event", { false }, fatal) {
                    runBackendRunnerLoop({ false }, { retries++ }) {
                        launch { throw failure }
                        awaitCancellation()
                    }
                }
                deadline.join()
                assertSame(failure, fatal.await())
                val schedule = launchBackendRunner("schedule", { false }, fatal) {
                    runBackendRunnerLoop({ false }, { retries++ }) { throw Error("second failure") }
                }
                schedule.join()
                assertSame(failure, fatal.await())
                assertEquals(0, retries)
            }
        }
    }

    test("explicit stop and ordinary cancellation do not report a fatal runner") {
        runTest {
            supervisorScope {
                val fatal = CompletableDeferred<Throwable>()
                var stopping = false
                var retries = 0
                val schedule = launchBackendRunner("schedule", { stopping }, fatal) {
                    runBackendRunnerLoop({ stopping }, { retries++ }) {
                        stopping = true
                        throw IllegalStateException("stopped during iteration")
                    }
                }
                schedule.join()
                val cancelled = launchBackendRunner("await_event", { false }, fatal) {
                    runBackendRunnerLoop({ false }, { retries++ }) { throw CancellationException("cancelled") }
                }
                cancelled.join()
                val waiting = launchBackendRunner("await_event", { false }, fatal) { awaitCancellation() }
                runCurrent()
                waiting.cancelAndJoin()
                assertTrue(waiting.isCancelled)
                assertEquals(0, retries)
                assertFalse(fatal.isCompleted)
            }
        }
    }

    test("retry reporting failure stays fatal and preserves the triggering error") {
        runTest {
            supervisorScope {
                val fatal = CompletableDeferred<Throwable>()
                val reporterFailure = IllegalStateException("retry reporter failed")
                val transient = IllegalStateException("transient input")
                launchBackendRunner("schedule", { false }, fatal) {
                    runBackendRunnerLoop({ false }, { throw reporterFailure }) { throw transient }
                }.join()
                assertSame(reporterFailure, fatal.await())
                assertEquals(listOf(transient), reporterFailure.suppressedExceptions)
            }
        }
    }

    test("normal early exit is fatal unless shutdown has started") {
        runTest {
            val fatal = CompletableDeferred<Throwable>()
            launchBackendRunner("schedule", { false }, fatal) {}.join()
            assertEquals("The schedule runner ended unexpectedly.", fatal.await().message)
        }
    }
}
