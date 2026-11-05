package io.github.stream29.kodex.utils.coroutines

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

val coroutineCloseableTest by testSuite {
    test("use closes on normal completion and closeOnFailure keeps resource open on success") {
        var usedClosed = false
        val usedResult = CoroutineCloseable { usedClosed = true }.use { "ok" }
        assertEquals("ok", usedResult)
        assertTrue(usedClosed)

        var keptClosed = false
        val kept = CoroutineCloseable { keptClosed = true }.closeOnFailure { it }
        assertFalse(keptClosed)
        kept.close()
        assertTrue(keptClosed)
    }

    test("closeOnFailure closes in NonCancellable context and suppresses close failure") {
        val closeError = IllegalStateException("close failure")
        var closeWasActive = false
        var closeCalls = 0
        val resource = CoroutineCloseable {
            closeCalls++
            closeWasActive = currentCoroutineContext().isActive
            yield()
            throw closeError
        }
        var operationFailure: CancellationException? = null
        var observedFailure: Throwable? = null

        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                try {
                    resource.closeOnFailure {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (cancelled: CancellationException) {
                            operationFailure = cancelled
                            throw cancelled
                        }
                    }
                } catch (failure: Throwable) {
                    observedFailure = failure
                }
            }
            entered.await()
            job.cancelAndJoin()
        }

        val original = assertNotNull(operationFailure)
        assertSame(original, observedFailure)
        assertEquals(1, closeCalls)
        assertTrue(closeWasActive)
        val suppressed = assertIs<IllegalStateException>(original.suppressedExceptions.single())
        assertEquals(closeError.message, suppressed.message)
    }

    test("AutoCloseable closeOnFailure closes only on failure and suppresses close failure") {
        var closed = 0
        val auto = AutoCloseable {
            closed++
            throw IllegalArgumentException("auto close failed")
        }
        assertEquals(42, auto.closeOnFailure { 42 })
        assertEquals(0, closed)

        val primary = assertFailsWith<IllegalStateException> {
            auto.closeOnFailure { throw IllegalStateException("primary failure") }
        }
        assertEquals(1, closed)
        assertIs<IllegalArgumentException>(primary.suppressedExceptions.single())
    }
}
