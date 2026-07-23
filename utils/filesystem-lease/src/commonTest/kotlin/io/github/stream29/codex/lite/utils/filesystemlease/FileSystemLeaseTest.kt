package io.github.stream29.codex.lite.utils.filesystemlease

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

private suspend fun temporaryDirectory(): Path =
    Path(SystemTemporaryDirectory, "codex-lite-lease-${Random.nextLong()}").also {
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

                first.close()
                while (SystemCoroutineFileSystem.metadataOrNull(lock) != null) delay(1)
                FileSystemLease(
                    lockPath = lock,
                    duration = 30.seconds,
                ).close()
            }
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
