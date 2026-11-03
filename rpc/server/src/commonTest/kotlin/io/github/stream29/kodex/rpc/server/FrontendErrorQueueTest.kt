@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val frontendErrorQueueTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("admits one active and 64 waiting without dropping or reordering") { runTest {
        val gate = CompletableDeferred<Unit>()
        val observed = mutableListOf<String?>()
        val queue = FrontendErrorQueue(backgroundScope) { _, message ->
            observed += message
            if (message == "active") gate.await()
        }
        try {
            assertEquals(FrontendErrorQueue.Admission.ADMITTED, queue.admit("active"))
            runCurrent()
            repeat(64) { assertEquals(FrontendErrorQueue.Admission.ADMITTED, queue.admit("$it")) }
            assertEquals(FrontendErrorQueue.Admission.OVERLOADED, queue.admit("overflow"))
            gate.complete(Unit)
            runCurrent()
            assertEquals<List<String?>>(listOf("active") + (0 until 64).map(Int::toString), observed)
        } finally {
            queue.closeAndJoin()
        }
    } }

    test("enforces UTF-8 byte limit and preserves null") { runTest {
        val observed = mutableListOf<String?>()
        val queue = FrontendErrorQueue(backgroundScope) { _, message -> observed += message }
        try {
            assertEquals(FrontendErrorQueue.Admission.ADMITTED, queue.admit(null))
            assertEquals(FrontendErrorQueue.Admission.ADMITTED, queue.admit("中".repeat(2730) + "ab"))
            assertFailsWith<IllegalArgumentException> { queue.admit("中".repeat(2731)) }
            assertFailsWith<IllegalArgumentException> { queue.admit("a".repeat(8193)) }
            runCurrent()
            assertEquals(listOf(null, "中".repeat(2730) + "ab"), observed)
        } finally {
            queue.closeAndJoin()
        }
    } }

    test("runner failure does not replay or stop following event") { runTest {
        val observed = mutableListOf<String?>()
        val queue = FrontendErrorQueue(backgroundScope) { _, message ->
            observed += message
            if (message == "first") error("private runner failure")
        }
        try {
            queue.admit("first")
            queue.admit("second")
            runCurrent()
            assertEquals<List<String?>>(listOf("first", "second"), observed)
        } finally {
            queue.closeAndJoin()
        }
    } }

    test("admitted events retain their own immutable configuration snapshot") { runTest {
        val gate = CompletableDeferred<Unit>()
        val configured = mutableListOf(
            NotificationHook("old", setOf(NotificationHookType.StopUnhandledError), "fake-command"),
        )
        val observed = mutableListOf<List<String>>()
        val queue = FrontendErrorQueue(backgroundScope, hooks = { configured }) { hooks, message ->
            observed += hooks.map { it.name }
            if (message == "first") gate.await()
        }
        try {
            queue.admit("first")
            runCurrent()
            queue.admit("second")
            configured.clear()
            configured += NotificationHook("new", setOf(NotificationHookType.StopUnhandledError), "fake-command")
            queue.admit("third")
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(listOf("old"), listOf("old"), listOf("new")), observed)
        } finally { queue.closeAndJoin() }
    } }

    test("concurrent close cancels active and drops waiting") { runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val observed = mutableListOf<String?>()
        val queue = FrontendErrorQueue(backgroundScope) { _, message ->
            observed += message
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        queue.admit("active")
        runCurrent()
        assertTrue(started.isCompleted)
        queue.admit("waiting")
        val first = async { queue.closeAndJoin() }
        val second = async { queue.closeAndJoin() }
        first.await()
        second.await()
        assertTrue(cancelled.isCompleted)
        assertEquals<List<String?>>(listOf("active"), observed)
        assertEquals(FrontendErrorQueue.Admission.CLOSED, queue.admit("later"))
    } }
}
