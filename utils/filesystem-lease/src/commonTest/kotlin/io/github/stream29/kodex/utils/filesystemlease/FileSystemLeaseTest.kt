package io.github.stream29.kodex.utils.filesystemlease

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private suspend fun temporaryDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-lease-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

val fileSystemLeaseTest by testSuite {
    testFixture { temporaryDirectory() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("excludes another owner until release") { directory ->
            coroutineScope {
                val lock = Path(directory, "lock.json")
                val first = FileSystemLease(
                    lockPath = lock,
                    duration = 30.seconds,
                )

                assertFailsWith<FileSystemLeaseInUseException> {
                    FileSystemLease(
                        lockPath = lock,
                        duration = 30.seconds,
                    )
                }

                assertTrue(first.isActive)
                first.close()
                assertFalse(first.isActive)
                assertTrue(currentCoroutineContext().isActive)
                while (SystemCoroutineFileSystem.metadataOrNull(lock) != null) delay(1)
                FileSystemLease(
                    lockPath = lock,
                    duration = 30.seconds,
                ).close()
            }
        }

        test("releases when its parent scope is cancelled") { directory ->
            val parentJob = SupervisorJob()
            val parentScope = CoroutineScope(currentCoroutineContext() + parentJob)
            val lock = Path(directory, "lock.json")
            val lease = parentScope.FileSystemLease(
                lockPath = lock,
                duration = 30.seconds,
            )

            parentJob.cancelAndJoin()

            assertFalse(lease.isActive)
            assertNull(SystemCoroutineFileSystem.metadataOrNull(lock))
        }
    }
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
