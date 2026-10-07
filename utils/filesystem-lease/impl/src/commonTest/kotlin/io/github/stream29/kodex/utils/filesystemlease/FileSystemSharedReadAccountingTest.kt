package io.github.stream29.kodex.utils.filesystemlease

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Real owner/guard files; only directory-path lookup is cached to place mutex waiters exactly. */
@OptIn(ExperimentalCoroutinesApi::class)
public val fileSystemSharedReadAccountingTest by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-shared-accounting-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        val directory = this
        withContext(NonCancellable) { deleteAccountingDirectory(directory) }
    } asParameterForEach {
        test("unrelated gated B publication cannot block A last release or abandon its reference") { root ->
            val a = Path(root, "A")
            val b = Path(root, "B")
            val base = preparedReadDirectories(a, b)
            val bPublication = CompletableDeferred<Unit>()
            val allowB = CompletableDeferred<Unit>()
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    if (path == ownerPath(b, ReadOwnerSuffix)) {
                        bPublication.complete(Unit)
                        allowB.await()
                    }
                    base.writeString(path, content, append, mustCreate)
                }
            }
            try {
                withAccountingOwner { owner ->
                    val firstA = owner.FileSystemReadLease(a, fs)
                    coroutineScope {
                        val acquiringB = async { owner.FileSystemReadLease(b, fs) }
                        bPublication.await()
                        assertFalse(acquiringB.isCompleted)
                        // Real bound, shorter than the production 30-second
                        // cleanup wait. No virtual clock/production test knob.
                        accountingBound { firstA.closeAndJoin() }
                        assertTrue(firstA.coroutineContext.job.isCompleted)
                        assertFalse(SystemCoroutineFileSystem.exists(ownerPath(a, ReadOwnerSuffix)))
                        repeat(2) { firstA.closeAndJoin() }
                        repeat(2) {
                            val nextA = accountingBound { owner.FileSystemReadLease(a, fs) }
                            accountingBound { nextA.closeAndJoin() }
                            nextA.closeAndJoin()
                            assertFalse(SystemCoroutineFileSystem.exists(ownerPath(a, ReadOwnerSuffix)))
                        }
                        assertFalse(acquiringB.isCompleted)
                        assertTrue(owner.isActive)
                        allowB.complete(Unit)
                        acquiringB.await().closeAndJoin()
                    }
                    assertTrue(owner.coroutineContext.job.children.none())
                    owner.coroutineContext.job.cancelAndJoin()
                    assertTrue(SystemCoroutineFileSystem.list(a).isEmpty())
                    assertTrue(SystemCoroutineFileSystem.list(b).isEmpty())
                }
            } finally {
                allowB.complete(Unit)
            }
        }

        test("same-key concurrent acquisition shares one original owner and competing parent rejects") { directory ->
            val base = preparedReadDirectories(directory)
            val published = CompletableDeferred<Unit>()
            val allowPublication = CompletableDeferred<Unit>()
            var publications = 0
            val path = ownerPath(directory, ReadOwnerSuffix)
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    base.writeString(path, content, append, mustCreate)
                    if (path == ownerPath(directory, ReadOwnerSuffix)) {
                        publications += 1
                        published.complete(Unit)
                        allowPublication.await()
                    }
                }
            }
            try {
                withAccountingOwner { owner ->
                    withAccountingOwner { competitor ->
                        coroutineScope {
                            val first = async { owner.FileSystemReadLease(directory, fs) }
                            published.await()
                            val originalBytes = SystemCoroutineFileSystem.readString(path)
                            val second = async(start = CoroutineStart.UNDISPATCHED) {
                                owner.FileSystemReadLease(directory, fs)
                            }
                            val rival = async(start = CoroutineStart.UNDISPATCHED) {
                                runCatching { competitor.FileSystemReadLease(directory, fs) }
                            }
                            assertFalse(second.isCompleted)
                            assertFalse(rival.isCompleted)
                            allowPublication.complete(Unit)
                            val firstHandle = first.await()
                            val secondHandle = second.await()
                            assertTrue(rival.await().exceptionOrNull() is FileSystemLeaseInUseException)
                            assertEquals(1, publications)
                            assertEquals(originalBytes, SystemCoroutineFileSystem.readString(path))
                            repeat(2) { firstHandle.closeAndJoin() }
                            assertTrue(secondHandle.isActive)
                            assertEquals(originalBytes, SystemCoroutineFileSystem.readString(path))
                            secondHandle.closeAndJoin()
                            secondHandle.closeAndJoin()
                            assertFalse(SystemCoroutineFileSystem.exists(path))
                        }
                        assertTrue(competitor.coroutineContext.job.children.none())
                    }
                    assertTrue(owner.coroutineContext.job.children.none())
                }
            } finally {
                allowPublication.complete(Unit)
            }
        }

        test("cancelled same-key waiter consumes no reference and leaves no orphan or idle owner job") { directory ->
            val base = preparedReadDirectories(directory)
            val published = CompletableDeferred<Unit>()
            val allowPublication = CompletableDeferred<Unit>()
            var publications = 0
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    base.writeString(path, content, append, mustCreate)
                    if (path == ownerPath(directory, ReadOwnerSuffix)) {
                        publications += 1
                        published.complete(Unit)
                        allowPublication.await()
                    }
                }
            }
            try {
                withAccountingOwner { owner ->
                    coroutineScope {
                        val first = async { owner.FileSystemReadLease(directory, fs) }
                        published.await()
                        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                            owner.FileSystemReadLease(directory, fs)
                        }
                        assertFalse(waiter.isCompleted)
                        accountingBound { waiter.cancelAndJoin() }
                        assertFalse(first.isCompleted)
                        allowPublication.complete(Unit)
                        val firstHandle = first.await()
                        val nextHandle = owner.FileSystemReadLease(directory, fs)
                        assertEquals(1, publications)
                        firstHandle.closeAndJoin()
                        assertTrue(SystemCoroutineFileSystem.exists(ownerPath(directory, ReadOwnerSuffix)))
                        nextHandle.closeAndJoin()
                        nextHandle.closeAndJoin()
                    }
                    assertTrue(SystemCoroutineFileSystem.list(directory).isEmpty())
                    assertTrue(owner.coroutineContext.job.children.none())
                    // Idle-key removal must not change public acquisition semantics.
                    withAccountingOwner { nextOwner ->
                        val next = nextOwner.FileSystemReadLease(directory, fs)
                        assertEquals(2, publications)
                        next.closeAndJoin()
                    }
                }
            } finally {
                allowPublication.complete(Unit)
            }
        }

        test("cancelled receiver waiting on a contended key cannot publish or inherit another owner's lease") { directory ->
            val base = preparedReadDirectories(directory)
            val published = CompletableDeferred<Unit>()
            val allowPublication = CompletableDeferred<Unit>()
            var publications = 0
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    base.writeString(path, content, append, mustCreate)
                    if (path == ownerPath(directory, ReadOwnerSuffix)) {
                        publications += 1
                        published.complete(Unit)
                        allowPublication.await()
                    }
                }
            }
            try {
                withAccountingOwner { owner ->
                    withAccountingOwner { cancelledOwner ->
                        coroutineScope {
                            val first = async { owner.FileSystemReadLease(directory, fs) }
                            published.await()
                            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                                runCatching { cancelledOwner.FileSystemReadLease(directory, fs) }
                            }
                            assertFalse(waiting.isCompleted)
                            cancelledOwner.coroutineContext.job.cancelAndJoin()
                            allowPublication.complete(Unit)
                            val handle = first.await()
                            assertTrue(waiting.await().isFailure)
                            assertEquals(1, publications)
                            assertTrue(handle.isActive)
                            handle.closeAndJoin()
                            assertTrue(SystemCoroutineFileSystem.list(directory).isEmpty())
                        }
                    }
                    assertTrue(owner.isActive)
                    assertTrue(owner.coroutineContext.job.children.none())
                }
            } finally {
                allowPublication.complete(Unit)
            }
        }

        test("cancelled publication with same-key contender cleans undelivered owner before replacement") { directory ->
            val base = preparedReadDirectories(directory)
            val published = CompletableDeferred<Unit>()
            var publications = 0
            var deletions = 0
            val path = ownerPath(directory, ReadOwnerSuffix)
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    base.writeString(path, content, append, mustCreate)
                    if (path == ownerPath(directory, ReadOwnerSuffix)) {
                        publications += 1
                        if (publications == 1) {
                            published.complete(Unit)
                            awaitCancellation()
                        }
                    }
                }
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path == ownerPath(directory, ReadOwnerSuffix)) deletions += 1
                    base.delete(path, mustExist)
                }
            }
            withAccountingOwner { owner ->
                coroutineScope {
                    val first = async { owner.FileSystemReadLease(directory, fs) }
                    published.await()
                    val next = async(start = CoroutineStart.UNDISPATCHED) {
                        owner.FileSystemReadLease(directory, fs)
                    }
                    assertFalse(next.isCompleted)
                    accountingBound { first.cancelAndJoin() }
                    val replacement = accountingBound { next.await() }
                    assertEquals(2, publications)
                    assertEquals(1, deletions)
                    assertTrue(replacement.isActive)
                    replacement.closeAndJoin()
                    assertEquals(2, deletions)
                    assertFalse(SystemCoroutineFileSystem.exists(path))
                }
                assertTrue(SystemCoroutineFileSystem.list(directory).isEmpty())
                assertTrue(owner.isActive)
                assertTrue(owner.coroutineContext.job.children.none())
            }
        }

        test("physical cleanup keeps its real deadline and saved timeout without retaining a shared reference") { directory ->
            val base = preparedReadDirectories(directory)
            val path = ownerPath(directory, ReadOwnerSuffix)
            var publications = 0
            var deletions = 0
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    base.writeString(path, content, append, mustCreate)
                    if (path == ownerPath(directory, ReadOwnerSuffix)) publications += 1
                }
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path == ownerPath(directory, ReadOwnerSuffix)) {
                        deletions += 1
                        if (deletions == 1) awaitCancellation()
                    }
                    base.delete(path, mustExist)
                }
            }
            withAccountingOwner { owner ->
                val first = owner.FileSystemReadLease(directory, fs)
                // Exercise the unchanged production 10-second physical limit.
                // The test's 15-second real limit detects removal of that bound.
                val failure = withContext(Dispatchers.Default) {
                    withTimeout(15.seconds) {
                        runCatching { first.closeAndJoin() }.exceptionOrNull()
                    }
                }
                assertTrue(failure is TimeoutCancellationException)
                repeat(2) {
                    assertSame(failure, assertFailsWith<TimeoutCancellationException> { first.closeAndJoin() })
                }
                assertTrue(first.coroutineContext.job.isCompleted)
                assertTrue(owner.coroutineContext.job.children.none())
                assertEquals(1, deletions)
                assertTrue(SystemCoroutineFileSystem.exists(path))
                // Physical failure leaves bytes, not a renewable in-memory
                // reference. Acquisition must inspect those bytes and reject.
                assertFailsWith<FileSystemLeaseInUseException> { owner.FileSystemReadLease(directory, fs) }
                assertEquals(1, publications)
                SystemCoroutineFileSystem.delete(path)
                val next = owner.FileSystemReadLease(directory, fs)
                assertEquals(2, publications)
                assertSame(failure, assertFailsWith<TimeoutCancellationException> { first.closeAndJoin() })
                assertTrue(next.isActive)
                next.closeAndJoin()
                assertEquals(2, deletions)
                assertFalse(SystemCoroutineFileSystem.exists(path))
                assertTrue(owner.isActive)
                assertTrue(owner.coroutineContext.job.children.none())
            }
        }

        test("one-to-zero concurrent reacquire waits for old physical release and repeated old close cannot delete new owner") { directory ->
            val base = preparedReadDirectories(directory)
            val deleting = CompletableDeferred<Unit>()
            val allowDelete = CompletableDeferred<Unit>()
            var publications = 0
            var deletions = 0
            val path = ownerPath(directory, ReadOwnerSuffix)
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    base.writeString(path, content, append, mustCreate)
                    if (path == ownerPath(directory, ReadOwnerSuffix)) publications += 1
                }
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path == ownerPath(directory, ReadOwnerSuffix)) {
                        deletions += 1
                        if (deletions == 1) {
                            deleting.complete(Unit)
                            allowDelete.await()
                        }
                    }
                    base.delete(path, mustExist)
                }
            }
            try {
                withAccountingOwner { owner ->
                    withAccountingOwner { nextOwner ->
                        val first = owner.FileSystemReadLease(directory, fs)
                        coroutineScope {
                            val closing = async { first.closeAndJoin() }
                            deleting.await()
                            val next = async(start = CoroutineStart.UNDISPATCHED) {
                                nextOwner.FileSystemReadLease(directory, fs)
                            }
                            assertFalse(next.isCompleted)
                            assertEquals(1, publications)
                            assertTrue(SystemCoroutineFileSystem.exists(path))
                            allowDelete.complete(Unit)
                            closing.await()
                            val replacement = next.await()
                            val replacementBytes = SystemCoroutineFileSystem.readString(path)
                            repeat(2) { first.closeAndJoin() }
                            assertEquals(2, publications)
                            assertEquals(1, deletions)
                            assertTrue(replacement.isActive)
                            assertEquals(replacementBytes, SystemCoroutineFileSystem.readString(path))
                            replacement.closeAndJoin()
                            assertEquals(2, deletions)
                            assertFalse(SystemCoroutineFileSystem.exists(path))
                        }
                        assertTrue(nextOwner.coroutineContext.job.children.none())
                    }
                    owner.coroutineContext.job.cancelAndJoin()
                    assertFalse(SystemCoroutineFileSystem.exists(path))
                }
            } finally {
                allowDelete.complete(Unit)
            }
        }

        test("owner-loss concurrent reacquire waits for retiring job and never deletes or reuses foreign read owner") { directory ->
            val base = preparedReadDirectories(directory)
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)
            val cleanupRead = CompletableDeferred<Unit>()
            val allowCleanupRead = CompletableDeferred<Unit>()
            var foreignInstalled = false
            var readsAfterLoss = 0
            var publications = 0
            var deletions = 0
            val path = ownerPath(directory, ReadOwnerSuffix)
            val fs = object : CoroutineFileSystem by base {
                override suspend fun writeString(path: Path, content: String, append: Boolean, mustCreate: Boolean) {
                    base.writeString(path, content, append, mustCreate)
                    if (path == ownerPath(directory, ReadOwnerSuffix)) publications += 1
                }
                override suspend fun readString(path: Path): String {
                    if (foreignInstalled && path == ownerPath(directory, ReadOwnerSuffix)) {
                        readsAfterLoss += 1
                        // First read loses renewal ownership; second is cleanup.
                        if (readsAfterLoss == 2) {
                            cleanupRead.complete(Unit)
                            allowCleanupRead.await()
                        }
                    }
                    return base.readString(path)
                }
                override suspend fun delete(path: Path, mustExist: Boolean) {
                    if (path == ownerPath(directory, ReadOwnerSuffix)) deletions += 1
                    base.delete(path, mustExist)
                }
            }
            try {
                withAccountingOwner(dispatcher + scheduler) { owner ->
                    withAccountingOwner { nextOwner ->
                        val first = owner.FileSystemReadLease(directory, fs)
                        val now = Clock.System.now()
                        val foreign = LeaseJson.encodeToString(
                            FileSystemLeaseHeartbeat.serializer(),
                            FileSystemLeaseHeartbeat(999999, now - 1.seconds, now + 300.seconds),
                        )
                        SystemCoroutineFileSystem.writeString(path, foreign)
                        foreignInstalled = true
                        scheduler.advanceTimeBy(30.seconds.inWholeMilliseconds / 3)
                        scheduler.runCurrent()
                        cleanupRead.await()
                        coroutineScope {
                            val next = async(start = CoroutineStart.UNDISPATCHED) {
                                runCatching { nextOwner.FileSystemReadLease(directory, fs) }
                            }
                            assertFalse(next.isCompleted)
                            assertEquals(1, publications)
                            allowCleanupRead.complete(Unit)
                            assertTrue(next.await().exceptionOrNull() is FileSystemLeaseInUseException)
                        }
                        repeat(2) { first.closeAndJoin() }
                        assertEquals(foreign, SystemCoroutineFileSystem.readString(path))
                        assertEquals(0, deletions)
                        assertEquals(1, publications)
                        assertTrue(owner.isActive)
                        assertTrue(owner.coroutineContext.job.children.none())
                        // Remove only this test's injected foreign bytes, then
                        // demonstrate that the retired process-local owner is gone.
                        SystemCoroutineFileSystem.delete(path)
                        val fresh = nextOwner.FileSystemReadLease(directory, fs)
                        assertEquals(2, publications)
                        fresh.closeAndJoin()
                        assertFalse(SystemCoroutineFileSystem.exists(path))
                        assertTrue(nextOwner.coroutineContext.job.children.none())
                    }
                }
            } finally {
                allowCleanupRead.complete(Unit)
            }
        }
    }
}

private suspend fun preparedReadDirectories(vararg directories: Path): CoroutineFileSystem {
    val resolved = directories.associateWith {
        SystemCoroutineFileSystem.createDirectories(it)
        SystemCoroutineFileSystem.resolve(it)
    }
    return object : CoroutineFileSystem by SystemCoroutineFileSystem {
        override suspend fun createDirectories(path: Path, mustCreate: Boolean) {
            if (path !in resolved || mustCreate) SystemCoroutineFileSystem.createDirectories(path, mustCreate)
        }
        override suspend fun resolve(path: Path): Path =
            resolved[path] ?: SystemCoroutineFileSystem.resolve(path)
    }
}

private suspend fun <T> accountingBound(block: suspend () -> T): T =
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { block() } }

private suspend fun <T> withAccountingOwner(
    context: CoroutineContext = Dispatchers.Default,
    block: suspend (CoroutineScope) -> T,
): T = coroutineScope {
    val job = SupervisorJob(coroutineContext.job)
    val owner = CoroutineScope(coroutineContext + context + job)
    try {
        block(owner)
    } finally {
        withContext(NonCancellable) { job.cancelAndJoin() }
    }
}

private suspend fun deleteAccountingDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { deleteAccountingDirectory(it) }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
