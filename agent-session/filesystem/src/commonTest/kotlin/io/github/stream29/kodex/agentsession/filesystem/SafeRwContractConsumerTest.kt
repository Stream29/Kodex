package io.github.stream29.kodex.agentsession.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.SafeRw
import io.github.stream29.kodex.utils.readSession
import io.github.stream29.kodex.utils.writeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val safeRwContractConsumerTest by testSuite {
    test("read contract initializes an outer val after suspension") {
        val safe = SafeRw<List<Int>, MutableList<Int>>(mutableListOf(1))
        val observed: List<Int>

        safe.readSession { value ->
            yield()
            observed = value.toList()
        }

        assertEquals(listOf(1), observed)
    }

    test("write contract initializes an outer val after suspension") {
        val safe = SafeRw<List<Int>, MutableList<Int>>(mutableListOf(1))
        val observed: List<Int>

        safe.writeSession { value ->
            yield()
            value.add(2)
            observed = value.toList()
        }

        assertEquals(listOf(1, 2), observed)
        assertEquals(observed, safe.readSession { it.toList() })
    }

    test("suspended read can return non-locally and release its lock") {
        val safe = SafeRw<List<Int>, MutableList<Int>>(mutableListOf(1))

        assertEquals(listOf(1), returnFromReadSession(safe))
        withTimeout(5_000) {
            safe.writeSession { it.add(2) }
            assertEquals(listOf(1, 2), safe.readSession { it.toList() })
        }
    }

    test("suspended write can return non-locally and release its lock") {
        val safe = SafeRw<List<Int>, MutableList<Int>>(mutableListOf(1))

        assertEquals(listOf(1, 2), returnFromWriteSession(safe))
        withTimeout(5_000) {
            assertEquals(listOf(1, 2), safe.readSession { it.toList() })
            safe.writeSession { it.add(3) }
            assertEquals(listOf(1, 2, 3), safe.readSession { it.toList() })
        }
    }

    test("suspended read and write failures release their locks") {
        val safe = SafeRw<List<Int>, MutableList<Int>>(mutableListOf(1))

        val readFailure = assertFailsWith<IllegalStateException> {
            safe.readSession<_, Unit> {
                yield()
                error("read failed")
            }
        }
        assertEquals("read failed", readFailure.message)
        withTimeout(5_000) {
            safe.writeSession { it.add(2) }
        }

        val writeFailure = assertFailsWith<IllegalArgumentException> {
            safe.writeSession<_, Unit> {
                it.add(3)
                yield()
                throw IllegalArgumentException("write failed")
            }
        }
        assertEquals("write failed", writeFailure.message)
        withTimeout(5_000) {
            assertEquals(listOf(1, 2, 3), safe.readSession { it.toList() })
            safe.writeSession { it.add(4) }
        }
    }

    test("cancelling a suspended reader releases its lock") {
        val safe = SafeRw<List<Int>, MutableList<Int>>(mutableListOf(1))

        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val reader = launch {
                safe.readSession<_, Unit> {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            reader.cancelAndJoin()
        }

        withTimeout(5_000) {
            safe.writeSession { it.add(2) }
            assertEquals(listOf(1, 2), safe.readSession { it.toList() })
        }
    }

    test("cancelling a suspended writer releases its lock") {
        val safe = SafeRw<List<Int>, MutableList<Int>>(mutableListOf(1))

        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val writer = launch {
                safe.writeSession<_, Unit> {
                    it.add(2)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            writer.cancelAndJoin()
        }

        withTimeout(5_000) {
            assertEquals(listOf(1, 2), safe.readSession { it.toList() })
            safe.writeSession { it.add(3) }
            assertEquals(listOf(1, 2, 3), safe.readSession { it.toList() })
        }
    }
}

private suspend fun returnFromReadSession(
    safe: SafeRw<List<Int>, MutableList<Int>>,
): List<Int> {
    safe.readSession<_, Unit> {
        yield()
        if (it.isNotEmpty()) return it.toList()
    }
    error("read session did not return its nonempty value")
}

private suspend fun returnFromWriteSession(
    safe: SafeRw<List<Int>, MutableList<Int>>,
): List<Int> {
    safe.writeSession<_, Unit> {
        yield()
        it.add(2)
        if (it.isNotEmpty()) return it.toList()
    }
    error("write session did not return its nonempty value")
}
