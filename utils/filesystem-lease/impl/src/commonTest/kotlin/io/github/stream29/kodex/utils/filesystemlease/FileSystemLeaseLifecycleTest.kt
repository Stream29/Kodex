package io.github.stream29.kodex.utils.filesystemlease

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Real isolated filesystem, precise syscall gates, and virtual time only for renewal. */
@OptIn(ExperimentalCoroutinesApi::class)
public val fileSystemLeaseLifecycleTest by testSuite {
    testFixture { lifecycleDirectory() } closeWith {
        val directory = this
        withContext(NonCancellable) { deleteLifecycleDirectory(directory) }
    } asParameterForEach {
        for (kind in LeaseKind.entries) {
            test("$kind cancellation before publication leaves no owner") { directory ->
                publicationCancellation(directory, kind, publishFirst = false)
            }
            test("$kind cancellation after publication awaits exact-owner cleanup") { directory ->
                publicationCancellation(directory, kind, publishFirst = true)
            }
            test("$kind published foreign replacement is never deleted on cancellation") { directory ->
                publicationCancellation(directory, kind, publishFirst = true, replaceWithForeign = true)
            }
            test("$kind publication primary preserves identity with cleanup suppressed") { directory ->
                val path = kind.path(directory)
                val primary = IOException("$kind publication primary")
                val cleanup = IOException("$kind publication cleanup")
                var deletions = 0
                val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun writeString(
                        path: Path, content: String, append: Boolean, mustCreate: Boolean,
                    ) {
                        SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                        if (path == kind.path(directory)) throw primary
                    }
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        if (path == kind.path(directory)) {
                            deletions += 1
                            throw cleanup
                        }
                        SystemCoroutineFileSystem.delete(path, mustExist)
                    }
                }
                withLeaseOwner { owner ->
                    assertSame(primary, assertFailsWith<IOException> { kind.acquire(owner, directory, fs) })
                    assertSame(cleanup, primary.suppressedExceptions.single())
                    assertEquals(1, deletions)
                    assertTrue(SystemCoroutineFileSystem.exists(path))
                    assertTrue(owner.isActive)
                }
            }
            test("$kind caller cancellation retains primary and observes failed publication cleanup") { directory ->
                val cancellation = CancellationException("$kind caller cancellation")
                val cleanup = IOException("$kind cancelled publication cleanup")
                val published = CompletableDeferred<Unit>()
                val observed = CompletableDeferred<Throwable>()
                var deletions = 0
                val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun writeString(
                        path: Path, content: String, append: Boolean, mustCreate: Boolean,
                    ) {
                        SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                        if (path == kind.path(directory)) {
                            published.complete(Unit)
                            awaitCancellation()
                        }
                    }
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        if (path == kind.path(directory)) {
                            deletions += 1
                            throw cleanup
                        }
                        SystemCoroutineFileSystem.delete(path, mustExist)
                    }
                }
                withLeaseOwner { owner ->
                    coroutineScope {
                        val acquisition = launch {
                            try {
                                kind.acquire(owner, directory, fs)?.closeAndJoin()
                            } catch (failure: Throwable) {
                                observed.complete(failure)
                            }
                        }
                        published.await()
                        acquisition.cancel(cancellation)
                        acquisition.join()
                        val failure = observed.await()
                        // Coroutine debug recovery may copy a Job cancellation
                        // exception. Its original cause remains the admitted
                        // cancellation; injected filesystem errors are not copied.
                        assertTrue(generateSequence(failure) { it.cause }.any { it === cancellation })
                        assertTrue(generateSequence(failure) { it.cause }
                            .flatMap { it.suppressedExceptions.asSequence() }.any { it === cleanup })
                        assertEquals(1, deletions)
                        assertTrue(SystemCoroutineFileSystem.exists(kind.path(directory)))
                        assertTrue(owner.isActive)
                    }
                }
            }
        }

        for (kind in listOf(LeaseKind.EXCLUSIVE, LeaseKind.READ, LeaseKind.WRITE)) {
            test("$kind owner cancellation during publication waits for resource cleanup") { directory ->
                val published = CompletableDeferred<Unit>()
                val cleanupStarted = CompletableDeferred<Unit>()
                val allowCleanup = CompletableDeferred<Unit>()
                val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun writeString(
                        path: Path, content: String, append: Boolean, mustCreate: Boolean,
                    ) {
                        SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                        if (path == kind.path(directory)) {
                            published.complete(Unit)
                            awaitCancellation()
                        }
                    }
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        if (path == kind.path(directory)) {
                            cleanupStarted.complete(Unit)
                            allowCleanup.await()
                        }
                        SystemCoroutineFileSystem.delete(path, mustExist)
                    }
                }
                try {
                    withLeaseOwner { owner ->
                        coroutineScope {
                            val acquisition = launch { kind.acquire(owner, directory, fs)?.closeAndJoin() }
                            published.await()
                            owner.coroutineContext.job.cancel()
                            cleanupStarted.await()
                            assertFalse(owner.coroutineContext.job.isCompleted)
                            assertTrue(SystemCoroutineFileSystem.exists(kind.path(directory)))
                            allowCleanup.complete(Unit)
                            owner.coroutineContext.job.join()
                            assertFalse(SystemCoroutineFileSystem.exists(kind.path(directory)))
                            acquisition.join()
                            assertFalse(SystemCoroutineFileSystem.exists(Path(directory, GuardFileName)))
                        }
                    }
                } finally {
                    allowCleanup.complete(Unit)
                }
            }
        }

        for (kind in listOf(LeaseKind.READ, LeaseKind.WRITE)) {
            test("$kind guard release failure cleans an already acquired undelivered owner") { directory ->
                val primary = IOException("guard release primary")
                var ownerDeletions = 0
                val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        if (path.name == GuardFileName) throw primary
                        if (path == kind.path(directory)) ownerDeletions += 1
                        SystemCoroutineFileSystem.delete(path, mustExist)
                    }
                }
                withLeaseOwner { owner ->
                    assertSame(primary, assertFailsWith<IOException> { kind.acquire(owner, directory, fs) })
                    assertEquals(1, ownerDeletions)
                    assertFalse(SystemCoroutineFileSystem.exists(kind.path(directory)))
                    assertTrue(SystemCoroutineFileSystem.exists(Path(directory, GuardFileName)))
                    assertTrue(owner.isActive)
                }
            }

            test("$kind guard primary suppresses failure releasing its undelivered owner") { directory ->
                val primary = IOException("guard cleanup")
                val cleanup = IOException("undelivered owner cleanup")
                var ownerDeletions = 0
                val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        if (path.name == GuardFileName) throw primary
                        if (path == kind.path(directory)) {
                            ownerDeletions += 1
                            throw cleanup
                        }
                        SystemCoroutineFileSystem.delete(path, mustExist)
                    }
                }
                withLeaseOwner { owner ->
                    assertSame(primary, assertFailsWith<IOException> { kind.acquire(owner, directory, fs) })
                    assertSame(cleanup, primary.suppressedExceptions.single())
                    assertEquals(1, ownerDeletions)
                    assertTrue(SystemCoroutineFileSystem.exists(kind.path(directory)))
                    assertTrue(SystemCoroutineFileSystem.exists(Path(directory, GuardFileName)))
                }
            }

            test("$kind cancellation in post-publication guard cleanup awaits undelivered owner") { directory ->
                val guardCleanup = CompletableDeferred<Unit>()
                val allowGuard = CompletableDeferred<Unit>()
                val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        if (path.name == GuardFileName) {
                            guardCleanup.complete(Unit)
                            allowGuard.await()
                        }
                        SystemCoroutineFileSystem.delete(path, mustExist)
                    }
                }
                try {
                    withLeaseOwner { owner ->
                        coroutineScope {
                            val acquisition = launch { kind.acquire(owner, directory, fs)?.closeAndJoin() }
                            guardCleanup.await()
                            assertTrue(SystemCoroutineFileSystem.exists(kind.path(directory)))
                            acquisition.cancel()
                            assertFalse(acquisition.isCompleted)
                            allowGuard.complete(Unit)
                            acquisition.join()
                            assertFalse(SystemCoroutineFileSystem.exists(kind.path(directory)))
                            assertFalse(SystemCoroutineFileSystem.exists(Path(directory, GuardFileName)))
                            assertTrue(owner.isActive)
                        }
                    }
                } finally {
                    allowGuard.complete(Unit)
                }
            }
        }

        test("cancelled close waiter does not revoke cleanup and repeated waits retain exact failure") { directory ->
            val path = LeaseKind.EXCLUSIVE.path(directory)
            val cleanup = IOException("late cleanup failure")
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            var deletions = 0
            val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path == LeaseKind.EXCLUSIVE.path(directory)) {
                        deletions += 1
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                        throw cleanup
                    }
                    SystemCoroutineFileSystem.delete(path, mustExist)
                }
            }
            try {
                withLeaseOwner { owner ->
                    val lease = owner.FileSystemLease(path, fs, 30.seconds)
                    coroutineScope {
                        val waiter = launch { lease.closeAndJoin() }
                        cleanupStarted.await()
                        waiter.cancelAndJoin()
                        assertFalse(lease.coroutineContext.job.isCompleted)
                        assertTrue(SystemCoroutineFileSystem.exists(path))
                        allowCleanup.complete(Unit)
                        repeat(2) {
                            assertSame(cleanup, assertFailsWith<IOException> { lease.closeAndJoin() })
                        }
                    }
                    lease.close()
                    assertEquals(1, deletions)
                    assertTrue(owner.isActive)
                }
            } finally {
                allowCleanup.complete(Unit)
            }
        }

        test("shared repeated close releases only one reference and last close observes one failure") { directory ->
            val cleanup = IOException("shared last release")
            var deletions = 0
            val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path.name.endsWith(ReadOwnerSuffix)) {
                        deletions += 1
                        throw cleanup
                    }
                    SystemCoroutineFileSystem.delete(path, mustExist)
                }
            }
            withLeaseOwner { owner ->
                val first = owner.FileSystemReadLease(directory, fs)
                val last = owner.FileSystemReadLease(directory, fs)
                repeat(2) { first.close(); first.closeAndJoin() }
                assertEquals(0, deletions)
                assertTrue(last.isActive)
                repeat(2) {
                    assertSame(cleanup, assertFailsWith<IOException> { last.closeAndJoin() })
                }
                owner.coroutineContext.job.cancelAndJoin()
                assertEquals(1, deletions)
                assertSame(cleanup, assertFailsWith<IOException> { last.closeAndJoin() })
            }
        }

        test("a cleanup CancellationException is a saved release failure, not a lost close request") { directory ->
            val cleanup = CancellationException("filesystem cleanup cancellation")
            var deletions = 0
            val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path.name.endsWith(ReadOwnerSuffix)) {
                        deletions += 1
                        throw cleanup
                    }
                    SystemCoroutineFileSystem.delete(path, mustExist)
                }
            }
            withLeaseOwner { owner ->
                val lease = owner.FileSystemReadLease(directory, fs)
                repeat(2) {
                    assertSame(cleanup, assertFailsWith<CancellationException> { lease.closeAndJoin() })
                }
                assertEquals(1, deletions)
                assertTrue(owner.isActive)
            }
        }

        test("explicit lease close leaves parent and sibling active") { directory ->
            withLeaseOwner { owner ->
                val sibling = owner.launch { awaitCancellation() }
                val lease = owner.FileSystemLease(LeaseKind.EXCLUSIVE.path(directory), duration = 30.seconds)
                lease.closeAndJoin()
                assertTrue(owner.isActive)
                assertTrue(sibling.isActive)
                assertFalse(SystemCoroutineFileSystem.exists(LeaseKind.EXCLUSIVE.path(directory)))
                sibling.cancelAndJoin()
            }
        }

        test("acquisition cleanup never suppresses an exception onto itself") { directory ->
            val failure = IOException("same injected error")
            val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun writeString(
                    path: Path, content: String, append: Boolean, mustCreate: Boolean,
                ) {
                    SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                    throw failure
                }
                override suspend fun delete(path: Path, mustExist: Boolean) { throw failure }
            }
            withLeaseOwner { owner ->
                assertSame(failure, assertFailsWith<IOException> {
                    owner.FileSystemLease(LeaseKind.EXCLUSIVE.path(directory), fs, 30.seconds)
                })
                assertTrue(failure.suppressedExceptions.isEmpty())
            }
        }

        test("failed publication cannot delete malformed bytes whose owner identity is unknown") { directory ->
            val primary = IOException("partial publication")
            var deletions = 0
            val path = LeaseKind.EXCLUSIVE.path(directory)
            val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun writeString(
                    path: Path, content: String, append: Boolean, mustCreate: Boolean,
                ) {
                    SystemCoroutineFileSystem.writeString(path, "not-an-owner", append, mustCreate)
                    throw primary
                }
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    deletions += 1
                    SystemCoroutineFileSystem.delete(path, mustExist)
                }
            }
            withLeaseOwner { owner ->
                assertSame(primary, assertFailsWith<IOException> { owner.FileSystemLease(path, fs, 30.seconds) })
                assertEquals(1, primary.suppressedExceptions.size)
                assertEquals(0, deletions)
                assertEquals("not-an-owner", SystemCoroutineFileSystem.readString(path))
            }
        }

        for (kind in listOf(LeaseKind.EXCLUSIVE, LeaseKind.READ, LeaseKind.WRITE)) {
            test("$kind lease loss finishes its child without deleting the foreign owner") { directory ->
                val scheduler = TestCoroutineScheduler()
                val dispatcher = UnconfinedTestDispatcher(scheduler)
                val renewalStarted = CompletableDeferred<Unit>()
                val allowRenewal = CompletableDeferred<Unit>()
                var delivered = false
                var checkedRenewal = false
                val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun readString(path: Path): String {
                        if (delivered && !checkedRenewal && path == kind.path(directory)) {
                            checkedRenewal = true
                            renewalStarted.complete(Unit)
                            allowRenewal.await()
                        }
                        return SystemCoroutineFileSystem.readString(path)
                    }
                }
                try {
                    withLeaseOwner(dispatcher + scheduler) { owner ->
                        val lease = checkNotNull(kind.acquire(owner, directory, fs))
                        val childStopped = CompletableDeferred<Unit>()
                        val child = lease.launch {
                            try {
                                awaitCancellation()
                            } finally {
                                childStopped.complete(Unit)
                            }
                        }
                        delivered = true
                        // Manually advance only renewal. No auto-advancement of
                        // cleanup deadlines while real filesystem IO is pending.
                        scheduler.advanceTimeBy(30.seconds.inWholeMilliseconds / 3)
                        scheduler.runCurrent()
                        renewalStarted.await()
                        val foreign = foreignHeartbeat()
                        SystemCoroutineFileSystem.writeString(kind.path(directory), foreign)
                        allowRenewal.complete(Unit)
                        lease.coroutineContext.job.join()
                        assertFalse(lease.isActive)
                        assertTrue(child.isCompleted)
                        assertTrue(childStopped.isCompleted)
                        lease.closeAndJoin()
                        lease.closeAndJoin()
                        assertEquals(foreign, SystemCoroutineFileSystem.readString(kind.path(directory)))
                        assertTrue(owner.isActive)
                    }
                } finally {
                    allowRenewal.complete(Unit)
                }
            }
        }

        test("renewal primary plus release failure is observed once with exact suppressed identity") { directory ->
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)
            val primary = IOException("renew primary")
            val cleanup = IOException("renew release")
            val path = LeaseKind.EXCLUSIVE.path(directory)
            var delivered = false
            var renewFailed = false
            var deletions = 0
            val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun readString(path: Path): String {
                    if (path == LeaseKind.EXCLUSIVE.path(directory) && delivered && !renewFailed) {
                        renewFailed = true
                        throw primary
                    }
                    return SystemCoroutineFileSystem.readString(path)
                }
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path == LeaseKind.EXCLUSIVE.path(directory)) {
                        deletions += 1
                        throw cleanup
                    }
                    SystemCoroutineFileSystem.delete(path, mustExist)
                }
            }
            withLeaseOwner(dispatcher + scheduler) { owner ->
                val lease = owner.FileSystemLease(path, fs, 30.seconds)
                delivered = true
                scheduler.advanceTimeBy(30.seconds.inWholeMilliseconds / 3)
                scheduler.runCurrent()
                lease.coroutineContext.job.join()
                repeat(2) {
                    assertSame(primary, assertFailsWith<IOException> { lease.closeAndJoin() })
                }
                assertSame(cleanup, primary.suppressedExceptions.single())
                assertEquals(1, deletions)
                assertTrue(SystemCoroutineFileSystem.exists(path))
                assertTrue(owner.isActive)
            }
        }
    }
}

private enum class LeaseKind {
    EXCLUSIVE, READ, WRITE, GUARD;

    fun path(directory: Path): Path = when (this) {
        EXCLUSIVE -> Path(directory, "exclusive.json")
        READ -> ownerPath(directory, ReadOwnerSuffix)
        WRITE -> ownerPath(directory, WriteOwnerSuffix)
        GUARD -> Path(directory, GuardFileName)
    }

    suspend fun acquire(scope: CoroutineScope, directory: Path, fs: CoroutineFileSystem): FileSystemLease? =
        when (this) {
            EXCLUSIVE -> scope.FileSystemLease(path(directory), fs, 30.seconds)
            READ -> scope.FileSystemReadLease(directory, fs)
            WRITE -> scope.FileSystemWriteLease(directory, fs)
            GUARD -> withAcquisitionGuard(directory, fs) { null }
        }
}

private suspend fun publicationCancellation(
    directory: Path,
    kind: LeaseKind,
    publishFirst: Boolean,
    replaceWithForeign: Boolean = false,
) {
    val publicationGate = CompletableDeferred<Unit>()
    val foreign = foreignHeartbeat()
    val fs = object : CoroutineFileSystem by SystemCoroutineFileSystem {
        override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
            if (path == kind.path(directory)) {
                if (publishFirst) SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
                if (replaceWithForeign) SystemCoroutineFileSystem.writeString(path, foreign)
                publicationGate.complete(Unit)
                awaitCancellation()
            }
            SystemCoroutineFileSystem.writeString(path, content, append, mustCreate)
        }
    }
    withLeaseOwner { owner ->
        coroutineScope {
            val acquisition = launch { kind.acquire(owner, directory, fs)?.closeAndJoin() }
            publicationGate.await()
            assertEquals(publishFirst, SystemCoroutineFileSystem.exists(kind.path(directory)))
            acquisition.cancelAndJoin()
            if (replaceWithForeign) {
                assertEquals(foreign, SystemCoroutineFileSystem.readString(kind.path(directory)))
            } else {
                assertFalse(SystemCoroutineFileSystem.exists(kind.path(directory)))
            }
            if (kind != LeaseKind.GUARD) {
                assertFalse(SystemCoroutineFileSystem.exists(Path(directory, GuardFileName)))
            }
            assertTrue(owner.isActive)
        }
    }
}

private fun foreignHeartbeat(): String {
    val now = Clock.System.now()
    return LeaseJson.encodeToString(
        FileSystemLeaseHeartbeat.serializer(),
        FileSystemLeaseHeartbeat(999999, now - 1.seconds, now + 300.seconds),
    )
}

private suspend fun <T> withLeaseOwner(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend (CoroutineScope) -> T,
): T = coroutineScope {
    val ownerJob: Job = SupervisorJob(coroutineContext.job)
    val owner = CoroutineScope(coroutineContext + context + ownerJob)
    try {
        block(owner)
    } finally {
        withContext(NonCancellable) { ownerJob.cancelAndJoin() }
    }
}

private suspend fun lifecycleDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-lease-lifecycle-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteLifecycleDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { deleteLifecycleDirectory(it) }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
