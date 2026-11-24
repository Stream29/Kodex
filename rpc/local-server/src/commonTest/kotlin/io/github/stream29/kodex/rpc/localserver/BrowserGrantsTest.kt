@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.localserver

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.TestTimeSource

internal val browserGrantsTest by testSuite {
    test("credential is bound to exact origin and canonical directory, never registered twice") {
        val grants = BrowserGrants()
        val credential = "a".repeat(64)
        val origin = "http://127.0.0.1:18801"
        val request = LocalBrowserGrant(LocalBrowserGrantProtocol, origin, credential, "/project")
        grants.put(request, "/canonical/project")
        assertTrue(grants.originAllowed(origin))
        assertFalse(grants.originAllowed("http://127.0.0.1:18802"))
        assertFalse(grants.renew(credential, "http://localhost:18801"))
        val peer = Job()
        try {
            assertNull(grants.attach(credential, origin, "/project", peer))
            assertEquals("/canonical/project", grants.attach(credential, origin, "/canonical/project", peer))
            assertFailsWith<IllegalArgumentException> { grants.put(request, "/canonical/project") }
            grants.revoke(credential)
            assertTrue(peer.isCancelled)
            assertFalse(grants.renew(credential, origin))
            assertFalse(grants.originAllowed(origin))
        } finally { peer.cancel() }
    }
    test("Origin probes and socket admission do not renew leases; browser renewal does") {
        val clock = TestTimeSource()
        val grants = BrowserGrants(clock)
        val origin = "http://127.0.0.1:18801"
        val credential = "b".repeat(64)
        val request = LocalBrowserGrant(LocalBrowserGrantProtocol, origin, credential, "/project")
        grants.put(request, "/project")
        val peer = Job()
        try {
            clock += 40.seconds
            assertTrue(grants.originAllowed(origin))
            assertEquals("/project", grants.attach(credential, origin, "/project", peer))
            clock += 20.seconds
            grants.expire()
            assertFalse(grants.originAllowed(origin))
            assertTrue(peer.isCancelled)
            grants.put(request, "/project")
            clock += 40.seconds
            assertTrue(grants.renew(credential, origin))
            clock += 40.seconds
            assertTrue(grants.originAllowed(origin))
            clock += 20.seconds
            assertFalse(grants.renew(credential, origin))
        } finally { peer.cancel() }
    }
    test("capacity is bounded and invalid credentials or remote origins fail closed") {
        val grants = BrowserGrants()
        val origin = "http://127.0.0.1:18801"
        for ((password, host, protocol) in listOf(
            Triple("short", origin, LocalBrowserGrantProtocol),
            Triple("c".repeat(64), "http://remote.test:18801", LocalBrowserGrantProtocol),
            Triple("c".repeat(64), origin, LocalBrowserGrantProtocol + 1),
        )) assertFailsWith<IllegalArgumentException> {
            grants.put(LocalBrowserGrant(protocol, host, password, "/project"), "/project")
        }
        repeat(128) { index ->
            grants.put(LocalBrowserGrant(LocalBrowserGrantProtocol, origin, index.toString(16).padStart(64, '0'),
                "/project"), "/project")
        }
        assertFailsWith<IllegalStateException> {
            grants.put(LocalBrowserGrant(LocalBrowserGrantProtocol, origin, "f".repeat(64), "/project"), "/project")
        }
    }
}

internal val browserGrantExpiryTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("empty registry does not wake and active peers expire at the earliest renewed deadline") {
        runTest {
            var reads = 0
            val clock = object : TimeSource {
                override fun markNow(): TimeMark {
                    val mark = testScheduler.timeSource.markNow()
                    return object : TimeMark {
                        override fun elapsedNow(): Duration {
                            reads++
                            return mark.elapsedNow()
                        }
                    }
                }
            }
            val grants = BrowserGrants(clock)
            val first = Job()
            val second = Job()
            backgroundScope.launch { grants.monitorExpiry() }
            try {
                runCurrent()
                assertNull(grants.earliestExpiry.value)
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(0, reads)

                val origin = "http://127.0.0.1:18801"
                val a = "a".repeat(64)
                val b = "b".repeat(64)
                grants.put(LocalBrowserGrant(LocalBrowserGrantProtocol, origin, a, "/project"), "/project")
                assertEquals("/project", grants.attach(a, origin, "/project", first))
                assertEquals(180_000L, grants.earliestExpiry.value)
                advanceTimeBy(10_000)
                grants.put(LocalBrowserGrant(LocalBrowserGrantProtocol, origin, b, "/project"), "/project")
                assertEquals("/project", grants.attach(b, origin, "/project", second))
                advanceTimeBy(10_000)
                assertTrue(grants.renew(a, origin))
                assertEquals(190_000L, grants.earliestExpiry.value)

                advanceTimeBy(49_999)
                runCurrent()
                assertTrue(first.isActive)
                assertTrue(second.isActive)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(second.isCancelled)
                assertTrue(first.isActive)
                assertEquals(200_000L, grants.earliestExpiry.value)
                advanceTimeBy(10_000)
                runCurrent()
                assertTrue(first.isCancelled)
                assertNull(grants.earliestExpiry.value)
                val readsWhenEmpty = reads
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(readsWhenEmpty, reads)
            } finally {
                first.cancel()
                second.cancel()
            }
        }
    }
}
