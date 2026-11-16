package io.github.stream29.kodex.utils.kotlinxiocoroutines

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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

val coroutineUseContractTest by testSuite {
    test("returns the original value after suspended block and close") {
        val resource = SuspendingUseResource()
        val expected = Any()

        val actual = resource.use {
            assertSame(resource, it)
            yield()
            expected
        }

        assertSame(expected, actual)
        assertEquals(1, resource.closeCalls)
        assertEquals(1, resource.closeResumes)
        assertTrue(resource.closeWasActive)
    }

    test("completes suspended close before a non-local return") {
        val resource = SuspendingUseResource()

        assertEquals(19, returnFromUse(resource))
        assertEquals(1, resource.closeCalls)
        assertEquals(1, resource.closeResumes)
        assertTrue(resource.closeWasActive)
    }

    test("preserves cancellation identity and suppresses suspended close failure") {
        val closeFailure = IllegalStateException("close failed")
        val resource = SuspendingUseResource(closeFailure)
        var operationFailure: CancellationException? = null
        var observedFailure: Throwable? = null

        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val owner = launch {
                try {
                    resource.use {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (failure: CancellationException) {
                            operationFailure = failure
                            throw failure
                        }
                    }
                } catch (failure: Throwable) {
                    observedFailure = failure
                }
            }
            entered.await()
            owner.cancelAndJoin()
        }

        val original = assertNotNull(operationFailure)
        assertSame(original, observedFailure)
        val suppressed = assertIs<IllegalStateException>(original.suppressedExceptions.single())
        assertEquals(closeFailure.message, suppressed.message)
        // JVM coroutine stack recovery may retain the original close failure as the copied error's cause.
        assertTrue(suppressed === closeFailure || suppressed.cause === closeFailure)
        assertEquals(1, resource.closeCalls)
        assertEquals(1, resource.closeResumes)
        assertTrue(resource.closeWasActive)
    }
}

private suspend fun returnFromUse(resource: SuspendingUseResource): Int {
    resource.use<SuspendingUseResource, Int> {
        yield()
        return 19
    }
    return -1
}

private class SuspendingUseResource(private val failure: Throwable? = null) : CoroutineCloseable {
    var closeCalls: Int = 0
        private set
    var closeResumes: Int = 0
        private set
    var closeWasActive: Boolean = false
        private set

    override suspend fun close() {
        closeCalls++
        closeWasActive = currentCoroutineContext().isActive
        yield()
        closeResumes++
        failure?.let { throw it }
    }
}
