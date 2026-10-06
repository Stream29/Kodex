package io.github.stream29.kodex.app.settings

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The original queue assertions belong with its two real RPC consumers. */
@OptIn(ExperimentalCoroutinesApi::class)
val settingsUpdateQueueTest by testSuite {
    test("update queue drains accepted writes before closing its target") {
        val release = CompletableDeferred<Unit>()
        var written = false
        var closed = false
        val queue = SettingsUpdateQueue(testScope.backgroundScope)
        queue.submit { release.await(); written = true }
        testScope.runCurrent()
        queue.close { closed = true }
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
