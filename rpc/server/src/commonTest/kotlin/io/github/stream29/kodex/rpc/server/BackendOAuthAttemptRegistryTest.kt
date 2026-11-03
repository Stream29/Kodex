@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

val backendOAuthAttemptRegistryTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("terminal results occupy the bounded registry until their exact retention expires") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        try {
            val first = registry.reserve(OAuthTarget.Mcp("fake-0"))
            assertFalse(registry.finish(first, OAuthAttemptOutcome.Committed))
            assertTrue(registry.waiting(first))
            assertTrue(registry.accept(first))
            assertTrue(registry.finish(first, OAuthAttemptOutcome.Committed))
            repeat(127) { registry.reserve(OAuthTarget.Mcp("fake-${it + 1}")) }
            assertFailsWith<IllegalStateException> { registry.reserve(OAuthTarget.Mcp("overflow")) }
            assertEquals(OAuthAttemptOutcome.Committed, registry.await(first.id))
            runCurrent()
            advanceTimeBy(10.minutes - 1.milliseconds)
            runCurrent()
            assertFailsWith<IllegalStateException> { registry.reserve(OAuthTarget.Mcp("overflow")) }
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertFailsWith<IllegalStateException> { registry.await(first.id) }
            val replacement = registry.reserve(OAuthTarget.Mcp("replacement"))
            assertTrue(replacement.id > first.id)
        } finally { registry.closeAndJoin() }
    } }

    test("only pending release can win against acceptance; explicit cancel includes accepted work") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        try {
            val released = registry.reserve(OAuthTarget.Mcp("released"))
            assertTrue(registry.waiting(released))
            assertNotNull(registry.releasePending(released.id))
            assertFalse(registry.accept(released))
            assertEquals(OAuthAttemptOutcome.Released, registry.await(released.id))
            assertNull(registry.releasePending(released.id))

            val accepted = registry.reserve(OAuthTarget.Mcp("accepted"))
            assertTrue(registry.waiting(accepted))
            assertTrue(registry.accept(accepted))
            assertFalse(registry.finish(accepted, OAuthAttemptOutcome.TimedOut))
            assertNull(registry.releasePending(accepted.id))
            assertNotNull(registry.cancel(accepted.id))
            assertFalse(registry.finish(accepted, OAuthAttemptOutcome.Committed))
            assertEquals(OAuthAttemptOutcome.Cancelled, registry.await(accepted.id))
            assertNull(registry.cancel(accepted.id))
        } finally { registry.closeAndJoin() }
    } }

    test("concurrent callback acceptance and pending release have one winner") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope, capacity = 256)
        try {
            repeat(200) { number ->
                val attempt = registry.reserve(OAuthTarget.Mcp("race-$number"))
                assertTrue(registry.waiting(attempt))
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                val accept = async { gate.await(); registry.accept(attempt) }
                val release = async { gate.await(); registry.releasePending(attempt.id) }
                runCurrent()
                gate.complete(Unit)
                val accepted = accept.await()
                val released = release.await() != null
                assertTrue(accepted xor released)
                if (accepted) {
                    assertTrue(registry.finish(attempt, OAuthAttemptOutcome.Committed))
                    assertEquals(OAuthAttemptOutcome.Committed, registry.await(attempt.id))
                } else assertEquals(OAuthAttemptOutcome.Released, registry.await(attempt.id))
            }
        } finally { registry.closeAndJoin() }
    } }

    test("observers may cancel independently and all remaining observers see one result") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        try {
            val attempt = registry.reserve(OAuthTarget.Mcp("observer"))
            val cancelled = async { registry.await(attempt.id) }
            val first = async { registry.await(attempt.id) }
            val second = async { registry.await(attempt.id) }
            runCurrent()
            cancelled.cancelAndJoin()
            assertTrue(registry.waiting(attempt))
            assertTrue(registry.accept(attempt))
            assertTrue(registry.finish(attempt, OAuthAttemptOutcome.Committed))
            assertEquals(OAuthAttemptOutcome.Committed, first.await())
            assertEquals(OAuthAttemptOutcome.Committed, second.await())
            assertEquals(OAuthAttemptOutcome.Committed, registry.await(attempt.id))
        } finally { registry.closeAndJoin() }
    } }

    test("target exclusion applies only while active and backend close wakes existing observers") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        val target = OAuthTarget.Mcp("same-target")
        val first = registry.reserve(target)
        assertFailsWith<IllegalStateException> { registry.reserve(target) }
        assertTrue(registry.finish(first, OAuthAttemptOutcome.Failed))
        val next = registry.reserve(target)
        val observer = async { registry.await(next.id) }
        runCurrent()
        registry.closeAndJoin()
        assertEquals(OAuthAttemptOutcome.BackendStopped, observer.await())
        assertFailsWith<IllegalStateException> { registry.reserve(OAuthTarget.Mcp("after-close")) }
        assertFailsWith<IllegalStateException> { registry.await(first.id) }
    } }
}
