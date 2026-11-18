@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

val backendOAuthAttemptRegistryTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("cleanup frees capacity immediately; only the original operation retains its result") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        try {
            val first = registry.reserve(OAuthTarget.Mcp("fake-0"))
            assertFalse(registry.finish(first, OAuthAttemptOutcome.Committed))
            assertTrue(registry.waiting(first))
            assertTrue(registry.accept(first))
            assertTrue(registry.finish(first, OAuthAttemptOutcome.Committed))
            repeat(127) { registry.reserve(OAuthTarget.Mcp("fake-${it + 1}")) }
            assertFailsWith<IllegalStateException> { registry.reserve(OAuthTarget.Mcp("overflow")) }
            assertEquals(OAuthAttemptOutcome.Committed, first.result.await())
            registry.retire(first)
            assertNull(registry.cancel(first.id))
            val replacement = registry.reserve(OAuthTarget.Mcp("replacement"))
            assertTrue(replacement.id > first.id)
        } finally { registry.close() }
    } }

    test("only pending release can win against acceptance; explicit cancel includes accepted work") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        try {
            val released = registry.reserve(OAuthTarget.Mcp("released"))
            assertTrue(registry.waiting(released))
            assertTrue(registry.releasePending(released))
            assertFalse(registry.accept(released))
            assertEquals(OAuthAttemptOutcome.Released, released.result.await())
            assertFalse(registry.releasePending(released))

            val accepted = registry.reserve(OAuthTarget.Mcp("accepted"))
            assertTrue(registry.waiting(accepted))
            assertTrue(registry.accept(accepted))
            assertFalse(registry.finish(accepted, OAuthAttemptOutcome.TimedOut))
            assertFalse(registry.releasePending(accepted))
            assertNotNull(registry.cancel(accepted.id))
            assertFalse(registry.finish(accepted, OAuthAttemptOutcome.Committed))
            assertEquals(OAuthAttemptOutcome.Cancelled, accepted.result.await())
            assertNull(registry.cancel(accepted.id))
        } finally { registry.close() }
    } }

    test("concurrent callback acceptance and pending release have one winner") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope, capacity = 256)
        try {
            repeat(200) { number ->
                val attempt = registry.reserve(OAuthTarget.Mcp("race-$number"))
                assertTrue(registry.waiting(attempt))
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                val accept = async { gate.await(); registry.accept(attempt) }
                val release = async { gate.await(); registry.releasePending(attempt) }
                runCurrent()
                gate.complete(Unit)
                val accepted = accept.await()
                val released = release.await()
                assertTrue(accepted xor released)
                if (accepted) {
                    assertTrue(registry.finish(attempt, OAuthAttemptOutcome.Committed))
                    assertEquals(OAuthAttemptOutcome.Committed, attempt.result.await())
                } else assertEquals(OAuthAttemptOutcome.Released, attempt.result.await())
                registry.retire(attempt)
            }
        } finally { registry.close() }
    } }

    test("only after cleanup can the same target acquire a new attempt") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        try {
            val target = OAuthTarget.Mcp("observer")
            val attempt = registry.reserve(target)
            assertTrue(registry.waiting(attempt))
            assertTrue(registry.accept(attempt))
            assertTrue(registry.finish(attempt, OAuthAttemptOutcome.Committed))
            assertFailsWith<IllegalStateException> { registry.reserve(target) }
            registry.retire(attempt)
            val next = registry.reserve(target)
            assertNull(registry.cancel(attempt.id))
            assertTrue(registry.waiting(next))
        } finally { registry.close() }
    } }

    test("target exclusion applies only while active and backend close wakes existing observers") { runTest {
        val registry = BackendOAuthAttemptRegistry(backgroundScope)
        val target = OAuthTarget.Mcp("same-target")
        val first = registry.reserve(target)
        assertFailsWith<IllegalStateException> { registry.reserve(target) }
        assertTrue(registry.finish(first, OAuthAttemptOutcome.Failed))
        registry.retire(first)
        val next = registry.reserve(target)
        val observer = async { next.result.await() }
        runCurrent()
        registry.close()
        assertEquals(OAuthAttemptOutcome.BackendStopped, observer.await())
        assertFailsWith<IllegalStateException> { registry.reserve(OAuthTarget.Mcp("after-close")) }
        assertNull(registry.cancel(first.id))
    } }
}
