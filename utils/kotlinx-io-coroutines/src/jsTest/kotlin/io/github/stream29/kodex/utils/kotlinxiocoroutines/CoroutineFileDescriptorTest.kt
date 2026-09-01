package io.github.stream29.kodex.utils.kotlinxiocoroutines

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import node.fs.readdir
import node.fs.readlink
import kotlin.random.Random
import kotlin.test.assertEquals

val coroutineFileDescriptorTest by testSuite {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-coroutine-fd-${Random.nextLong()}").also {
            SystemCoroutineFileSystem.createDirectories(it)
        }
    } closeWith {
        deleteJsTestRoot(this)
    } asParameterForEach {
        test("source handle closes when cancelled during open handoff") { root ->
            val file = Path(root, "source.txt")
            SystemCoroutineFileSystem.writeString(file, "content")
            val resolvedFile = SystemCoroutineFileSystem.resolve(file)

            coroutineScope {
                val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                    SystemCoroutineFileSystem.useSource(file) {
                        error("Cancelled owner must not enter the source block.")
                    }
                }

                owner.cancelAndJoin()
                assertEquals(0, descriptorCount(resolvedFile))
            }
        }

        test("sink handle closes when cancelled during open handoff") { root ->
            val file = Path(root, "sink.txt")

            coroutineScope {
                val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                    SystemCoroutineFileSystem.useSink(file) {
                        error("Cancelled owner must not enter the sink block.")
                    }
                }

                owner.cancelAndJoin()
                val resolvedFile = SystemCoroutineFileSystem.resolve(file)
                assertEquals(0, descriptorCount(resolvedFile))
            }
        }
    }
}

private suspend fun descriptorCount(path: Path): Int =
    readdir("/proc/self/fd").count { descriptor ->
        runCatching {
            readlink("/proc/self/fd/$descriptor").removeSuffix(" (deleted)")
        }.getOrNull() == path.toString()
    }

private suspend fun deleteJsTestRoot(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteJsTestRoot(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
