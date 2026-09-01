package io.github.stream29.kodex.utils.filesystemlease

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

public val fileSystemLeaseImplTest by testSuite {
    testFixture { temporaryDirectory() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("exclusive lease is released before its owner completes") { directory ->
            val owner = testOwner()
            val lock = Path(directory, "lock.json")
            val lease = owner.scope.FileSystemLease(lock, duration = 30.seconds)

            assertFailsWith<FileSystemLeaseInUseException> {
                owner.scope.FileSystemLease(lock, duration = 30.seconds)
            }
            assertTrue(lease.isActive)

            owner.job.cancelAndJoin()

            assertFalse(lease.isActive)
            assertNull(SystemCoroutineFileSystem.metadataOrNull(lock))
        }

        test("read handles share one owner within the same scope") { directory ->
            val owner = testOwner()
            val first = owner.scope.FileSystemReadLease(directory)
            val second = owner.scope.FileSystemReadLease(directory)
            val ownerPath = Path(directory, "${processId()}.read.lock")

            assertTrue(SystemCoroutineFileSystem.exists(ownerPath))
            closeLease(first)
            assertTrue(SystemCoroutineFileSystem.exists(ownerPath))
            closeLease(second)
            assertNull(SystemCoroutineFileSystem.metadataOrNull(ownerPath))
            owner.job.cancelAndJoin()
        }

        test("read and write leases exclude each other") { directory ->
            val readerOwner = testOwner()
            val reader = readerOwner.scope.FileSystemReadLease(directory)
            assertFailsWith<FileSystemLeaseInUseException> {
                readerOwner.scope.FileSystemWriteLease(directory)
            }
            closeLease(reader)
            readerOwner.job.cancelAndJoin()

            val writerOwner = testOwner()
            val writer = writerOwner.scope.FileSystemWriteLease(directory)
            assertFailsWith<FileSystemLeaseInUseException> {
                writerOwner.scope.FileSystemReadLease(directory)
            }
            closeLease(writer)
            writerOwner.job.cancelAndJoin()
        }

        test("read lease is released before its owner completes") { directory ->
            val owner = testOwner()
            val ownerPath = Path(directory, "${processId()}.read.lock")
            val lease = owner.scope.FileSystemReadLease(directory)

            owner.job.cancelAndJoin()

            assertFalse(lease.isActive)
            assertNull(SystemCoroutineFileSystem.metadataOrNull(ownerPath))
        }

        test("write lease is released before its owner completes") { directory ->
            val owner = testOwner()
            val ownerPath = Path(directory, "${processId()}.write.lock")
            val lease = owner.scope.FileSystemWriteLease(directory)

            owner.job.cancelAndJoin()

            assertFalse(lease.isActive)
            assertNull(SystemCoroutineFileSystem.metadataOrNull(ownerPath))
        }

        test("malformed owner heartbeat fails closed") { directory ->
            val owner = testOwner()
            SystemCoroutineFileSystem.writeString(
                Path(directory, "999999.read.lock"),
                "not-json",
            )

            assertFailsWith<Throwable> {
                owner.scope.FileSystemWriteLease(directory)
            }
            owner.job.cancelAndJoin()
        }
    }
}

private data class TestOwner(
    val job: Job,
    val scope: CoroutineScope,
)

private suspend fun testOwner(): TestOwner {
    val job = SupervisorJob()
    return TestOwner(
        job = job,
        scope = CoroutineScope(currentCoroutineContext() + job),
    )
}

private suspend fun temporaryDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-lease-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private suspend fun closeLease(lease: FileSystemLease) {
    lease.close()
    lease.coroutineContext.job.join()
}
