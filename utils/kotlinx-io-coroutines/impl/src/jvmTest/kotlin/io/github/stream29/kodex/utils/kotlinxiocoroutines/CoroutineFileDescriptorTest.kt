package io.github.stream29.kodex.utils.kotlinxiocoroutines

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertEquals

val coroutineFileDescriptorTest by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-coroutine-fd-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        deleteJvmTestRoot(this)
    } asParameterForEach {
        test("source descriptor closes before cancelled owner completes") { root ->
            val file = Path(root, "source.txt")
            SystemCoroutineFileSystem.writeString(file, "content")

            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val owner = launch {
                    SystemCoroutineFileSystem.useSource(file) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }

                entered.await()
                assertEquals(1, descriptorCount(file))
                owner.cancelAndJoin()
                assertEquals(0, descriptorCount(file))
            }
        }

        test("sink descriptor closes before cancelled owner completes") { root ->
            val file = Path(root, "sink.txt")

            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val owner = launch {
                    SystemCoroutineFileSystem.useSink(file) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }

                entered.await()
                assertEquals(1, descriptorCount(file))
                owner.cancelAndJoin()
                assertEquals(0, descriptorCount(file))
            }
        }
    }
}

private fun descriptorCount(path: Path): Long {
    val expected = java.nio.file.Path.of(path.toString()).toRealPath().toString()
    return Files.list(java.nio.file.Path.of("/proc/self/fd")).use { descriptors ->
        descriptors.filter { descriptor ->
            runCatching {
                Files.readSymbolicLink(descriptor).toString().removeSuffix(" (deleted)")
            }.getOrNull() == expected
        }.count()
    }
}

private suspend fun deleteJvmTestRoot(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteJvmTestRoot(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
