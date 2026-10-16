package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The original queue assertions belong with its two real RPC consumers. */
@OptIn(ExperimentalCoroutinesApi::class)
val settingsUpdateQueueTest by testSuite {
    test("command cancellation with live parent rejects later admission and never claims successful drain") {
        val parent = Job()
        val scope = CoroutineScope(testScope.backgroundScope.coroutineContext + parent)
        val errors = mutableListOf<Throwable>()
        val queue = SettingsUpdateQueue(scope, { errors += it })
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        try {
            assertTrue(queue.submit { release.await(); throw CancellationException("command only") })
            assertTrue(queue.submit { writes += 2 })
            testScope.runCurrent()
            release.complete(Unit)
            testScope.runCurrent()
            assertTrue(parent.isActive)
            assertTrue(errors.isEmpty(), "Do not report cancellation as business failure.")
            assertFalse(queue.submit { writes += 3 })
            assertEquals(1, errors.size, "Rejected admission has an ordinary owner failure.")
            assertTrue(writes.isEmpty())
            var cleanup = false
            queue.close { drained -> cleanup = true; assertFalse(drained) }
            assertTrue(cleanup)
            assertFalse(queue.drained)
        } finally { parent.cancel(); testScope.runCurrent() }
    }
    test("whole parent cancellation also rejects admission and is not a successful drain") {
        val parent = Job()
        val queue = SettingsUpdateQueue(CoroutineScope(testScope.backgroundScope.coroutineContext + parent), {})
        val release = CompletableDeferred<Unit>()
        var written = false
        try {
            assertTrue(queue.submit { release.await(); written = true })
            testScope.runCurrent()
            parent.cancel()
            testScope.runCurrent()
            assertFalse(queue.submit { written = true })
            var drained: Boolean? = null
            queue.close { drained = it }
            assertEquals(false, drained)
            assertFalse(written)
        } finally { parent.cancel(); testScope.runCurrent() }
    }
    test("update queue drains accepted writes before closing its target") {
        val release = CompletableDeferred<Unit>()
        var written = false
        var closed = false
        val queue = SettingsUpdateQueue(testScope.backgroundScope)
        queue.submit { release.await(); written = true }
        testScope.runCurrent()
        queue.close { drained -> assertTrue(drained); closed = true }
        assertFalse(closed)
        release.complete(Unit)
        testScope.runCurrent()
        assertTrue(written)
        assertTrue(closed)
    }
    test("update queue continues after a failed write") {
        var written = false
        var reported = false
        val queue = SettingsUpdateQueue(testScope.backgroundScope)
        queue.submit(reportError = { reported = true }) { error("write failed") }
        queue.submit { written = true }
        testScope.runCurrent()
        assertTrue(reported)
        assertTrue(written)
        queue.close {}
    }
    test("closing rejects later admissions without dropping queued writes or changing order") {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        val queue = SettingsUpdateQueue(testScope.backgroundScope)
        queue.submit { release.await(); writes += 1 }
        queue.submit { writes += 2 }
        testScope.runCurrent()
        queue.close()
        queue.submit { writes += 3 }
        release.complete(Unit)
        testScope.runCurrent()
        assertEquals(listOf(1, 2), writes)
    }
}
