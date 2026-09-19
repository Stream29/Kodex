@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val suspendMutableStateFlowTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("value replayCache and collection use the original StateFlow without invoking CAS") {
        runTest {
            val source = MutableStateFlow(1)
            val state = source.asSuspendMutableStateFlow { _, _ -> error("Unexpected CAS") }
            val received = mutableListOf<Int>()
            val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
                state.take(2).toList(received)
            }
            assertEquals(1, state.value)
            assertEquals(source.replayCache, state.replayCache)
            source.value = 2
            collection.join()
            assertEquals(listOf(1, 2), received)
            assertEquals(2, state.value)
            assertEquals(source.replayCache, state.replayCache)
        }
    }

    test("CAS forwards nullable expected and updated values without changing the local snapshot") {
        runTest {
            val source = MutableStateFlow<String?>(null)
            val arguments = mutableListOf<Pair<String?, String?>>()
            val state = source.asSuspendMutableStateFlow { expect, update ->
                arguments += expect to update
                expect == null
            }
            assertTrue(state.compareAndSet(null, "accepted"))
            assertFalse(state.compareAndSet("stale", null))
            assertEquals(listOf(null to "accepted", "stale" to null), arguments)
            assertEquals(null, state.value)
        }
    }

    for (operation in listOf("update", "getAndUpdate", "updateAndGet")) {
        test("$operation returns from the successful comparison despite a lagging local value") {
            runTest {
                val source = MutableStateFlow(5)
                var attempts = 0
                val state = source.asSuspendMutableStateFlow { expect, update ->
                    attempts++
                    assertEquals(5, expect)
                    assertEquals(6, update)
                    true
                }
                when (operation) {
                    "update" -> assertEquals(Unit, state.update { it + 1 })
                    "getAndUpdate" -> assertEquals(5, state.getAndUpdate { it + 1 })
                    "updateAndGet" -> assertEquals(6, state.updateAndGet { it + 1 })
                }
                assertEquals(1, attempts)
                assertEquals(5, state.value)
                assertEquals(0L, currentTime)
            }
        }

        test("$operation retries after 50ms using the newest local snapshot") {
            runTest {
                val source = MutableStateFlow(10)
                val arguments = mutableListOf<Pair<Int, Int>>()
                val times = mutableListOf<Long>()
                val state = source.asSuspendMutableStateFlow { expect, update ->
                    arguments += expect to update
                    times += currentTime
                    arguments.size == 2
                }
                launch {
                    delay(25)
                    source.value = 20
                }
                val result = async {
                    when (operation) {
                        "update" -> state.update { it + 1 }
                        "getAndUpdate" -> state.getAndUpdate { it + 1 }
                        else -> state.updateAndGet { it + 1 }
                    }
                }
                runCurrent()
                advanceTimeBy(49)
                runCurrent()
                assertEquals(listOf(10 to 11), arguments)
                assertFalse(result.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                val expected: Any = when (operation) {
                    "update" -> Unit
                    "getAndUpdate" -> 20
                    else -> 21
                }
                assertEquals(expected, result.await())
                assertEquals(listOf(10 to 11, 20 to 21), arguments)
                assertEquals(listOf(0L, 50L), times)
                assertEquals(20, state.value)
            }
        }
    }

    test("comparison failures can retry with no new state emission") {
        runTest {
            val source = MutableStateFlow(7)
            var attempts = 0
            var transforms = 0
            val state = source.asSuspendMutableStateFlow { _, _ -> ++attempts == 3 }
            assertEquals(8, state.updateAndGet {
                transforms++
                it + 1
            })
            assertEquals(3, attempts)
            assertEquals(3, transforms)
            assertEquals(100L, currentTime)
            assertEquals(7, state.value)
        }
    }

    for (operation in listOf("update", "getAndUpdate", "updateAndGet")) {
        test("$operation stops when cancelled during the retry delay") {
            runTest {
                var attempts = 0
                val state = MutableStateFlow(0).asSuspendMutableStateFlow { _, _ ->
                    attempts++
                    false
                }
                val update = launch {
                    when (operation) {
                        "update" -> state.update { it + 1 }
                        "getAndUpdate" -> state.getAndUpdate { it + 1 }
                        else -> state.updateAndGet { it + 1 }
                    }
                }
                runCurrent()
                assertEquals(1, attempts)
                update.cancelAndJoin()
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(1, attempts)
                assertTrue(update.isCancelled)
            }
        }
    }

    test("CAS failures and cancellation escape instead of entering the retry loop") {
        runTest {
            for (failure in listOf(
                SessionNotActive(),
                IllegalArgumentException("unknown failure"),
                CancellationException("cancel comparison"),
            )) {
                var attempts = 0
                val state = MutableStateFlow(0).asSuspendMutableStateFlow { _, _ ->
                    attempts++
                    throw failure
                }
                val actual = assertFailsWith<Throwable> { state.update { it + 1 } }
                assertEquals(failure::class, actual::class)
                assertEquals(failure.message, actual.message)
                assertEquals(1, attempts)
                assertEquals(0L, currentTime)
            }
        }
    }

    test("transform failure never invokes CAS") {
        runTest {
            var calls = 0
            val state = MutableStateFlow(0).asSuspendMutableStateFlow { _, _ ->
                calls++
                true
            }
            assertFailsWith<IllegalArgumentException> {
                state.getAndUpdate { throw IllegalArgumentException("transform failed") }
            }
            assertEquals(0, calls)
            assertEquals(0L, currentTime)
        }
    }

    test("an already cancelled update does not invoke its transform or CAS") {
        runTest {
            var calls = 0
            var transforms = 0
            val state = MutableStateFlow(0).asSuspendMutableStateFlow { _, _ ->
                calls++
                true
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                assertFailsWith<CancellationException> {
                    state.updateAndGet {
                        transforms++
                        it + 1
                    }
                }
            }.join()
            assertEquals(0, calls)
            assertEquals(0, transforms)
        }
    }
}
