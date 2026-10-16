package io.github.stream29.kodex.utils.kotlinxiocoroutines

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.readlink
import kotlin.random.Random
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
val coroutineFileDescriptorTest by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-coroutine-fd-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        deleteLinuxTestRoot(this)
    } asParameterForEach {
        test("source descriptor closes before cancelled owner completes") { root ->
            val file = Path(root, "source.txt")
            SystemCoroutineFileSystem.writeString(file, "content")
            val resolvedFile = SystemCoroutineFileSystem.resolve(file)

            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val owner = launch {
                    SystemCoroutineFileSystem.useSource(file) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }

                entered.await()
                assertEquals(1, descriptorCount(resolvedFile), descriptorTargets(resolvedFile))
                owner.cancelAndJoin()
                assertEquals(0, descriptorCount(resolvedFile), descriptorTargets(resolvedFile))
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
                val resolvedFile = SystemCoroutineFileSystem.resolve(file)
                assertEquals(1, descriptorCount(resolvedFile), descriptorTargets(resolvedFile))
                owner.cancelAndJoin()
                assertEquals(0, descriptorCount(resolvedFile), descriptorTargets(resolvedFile))
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun descriptorCount(path: Path): Int {
    val targets = openDescriptorTargets()
    return targets.count { it.removeSuffix(" (deleted)") == path.toString() }
}

@OptIn(ExperimentalForeignApi::class)
private fun descriptorTargets(path: Path): String =
    "expected=$path, targets=${openDescriptorTargets()}"

@OptIn(ExperimentalForeignApi::class)
private fun openDescriptorTargets(): List<String> {
    val directory = checkNotNull(opendir("/proc/self/fd"))
    try {
        val targets = mutableListOf<String>()
        while (true) {
            val entry = readdir(directory) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            val target = readLink("/proc/self/fd/$name") ?: continue
            targets += target
        }
        return targets
    } finally {
        closedir(directory)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readLink(path: String): String? {
    val bytes = ByteArray(4096)
    val length = bytes.usePinned { pinned ->
        readlink(path, pinned.addressOf(0), (bytes.size - 1).convert())
    }
    if (length < 0) return null
    bytes[length.toInt()] = 0
    return bytes.usePinned { it.addressOf(0).toKString() }
}

private suspend fun deleteLinuxTestRoot(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteLinuxTestRoot(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
