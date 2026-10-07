package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import io.github.stream29.kodex.app.migration.KodexHomeHandle
import io.github.stream29.kodex.app.migration.MigrationVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val loggingStartupTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    for (cancelled in listOf(false, true)) {
        test("logging startup retains primary and waits for Home cleanup cancellationException=$cancelled") {
            val primary = if (cancelled) CancellationException("logging startup")
                else IOException("logging startup")
            val cleanup = IOException("Home release")
            val releaseStarted = CompletableDeferred<Unit>()
            val releaseAllowed = CompletableDeferred<Unit>()
            var releases = 0
            val home = object : KodexHomeHandle {
                override val home = Path("/not-used-for-IO")
                override val version = MigrationVersion(0, 4, 8)
                override fun close() = Unit
                override suspend fun closeAndJoin() {
                    releases++
                    releaseStarted.complete(Unit)
                    releaseAllowed.await()
                    throw cleanup
                }
            }
            val reports = mutableListOf<Throwable>()
            val operation = async {
                initializeCliLogging(
                    home,
                    initialize = { throw primary },
                    report = { reports += it },
                )
            }
            try {
                withTimeout(5.seconds) { releaseStarted.await() }
                assertFalse(operation.isCompleted)
                assertTrue(reports.isEmpty())
            } finally {
                releaseAllowed.complete(Unit)
            }
            assertFalse(withTimeout(5.seconds) { operation.await() })
            assertEquals(1, releases)
            assertSame(primary, reports.single())
            assertSame(cleanup, primary.suppressedExceptions.single())
        }
    }
    test("cancelled logging caller still awaits and reports Home release failure") {
        val initializing = CompletableDeferred<Unit>()
        val releasing = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        val cleanup = IOException("Home release after caller cancellation")
        val reports = mutableListOf<Throwable>()
        val home = object : KodexHomeHandle {
            override val home = Path("/not-used-for-IO")
            override val version = MigrationVersion(0, 4, 8)
            override fun close() = Unit
            override suspend fun closeAndJoin() {
                releasing.complete(Unit)
                allowRelease.await()
                throw cleanup
            }
        }
        val operation = async {
            initializeCliLogging(home, initialize = {
                initializing.complete(Unit)
                awaitCancellation()
            }, report = { reports += it })
        }
        try {
            withTimeout(5.seconds) { initializing.await() }
            operation.cancel(CancellationException("cancel actual logging caller"))
            withTimeout(5.seconds) { releasing.await() }
            assertFalse(operation.isCompleted)
        } finally {
            allowRelease.complete(Unit)
        }
        withTimeout(5.seconds) { joinAll(operation) }
        assertEquals(1, reports.size)
        assertTrue(reports.single() is CancellationException)
        assertSame(cleanup, reports.single().suppressedExceptions.single())
    }
    test("successful logging startup retains Home for Application") {
        var closed = false
        val home = object : KodexHomeHandle {
            override val home = Path("/not-used-for-IO")
            override val version = MigrationVersion(0, 4, 8)
            override fun close() { closed = true }
            override suspend fun closeAndJoin() { closed = true }
        }
        var initialized: Path? = null
        assertTrue(initializeCliLogging(home, initialize = { initialized = it }, report = { throw it }))
        assertEquals(home.home, initialized)
        assertFalse(closed)
    }
}
