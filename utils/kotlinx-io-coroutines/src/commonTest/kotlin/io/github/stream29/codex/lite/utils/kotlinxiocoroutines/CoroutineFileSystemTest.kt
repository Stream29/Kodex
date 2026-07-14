package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import de.infix.testBalloon.framework.core.testSuite

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun temporaryRoot(): Path =
    Path(SystemTemporaryDirectory, "codex-lite-coroutine-fs-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

val coroutineFileSystemTest by testSuite {
    testFixture { temporaryRoot() } closeWith { deleteRecursively(this) } asParameterForEach {
        test("reads writes lists and deletes files") { root ->
            val child = Path(root, "dir/file.txt")
            SystemCoroutineFileSystem.createDirectories(child.parent!!)
            SystemCoroutineFileSystem.writeString(child, "hello\n")
            SystemCoroutineFileSystem.writeString(child, "world\n", append = true)

            assertTrue(SystemCoroutineFileSystem.exists(child))
            assertEquals("hello\nworld\n", SystemCoroutineFileSystem.readString(child))
            assertTrue(SystemCoroutineFileSystem.metadataOrNull(child)?.isRegularFile == true)
            assertEquals(listOf(child), SystemCoroutineFileSystem.list(child.parent!!))

            SystemCoroutineFileSystem.delete(child)
            assertFalse(SystemCoroutineFileSystem.exists(child))
        }

        test("reads writes and copies binary files") { root ->
            val sourceFile = Path(root, "source.bin")
            val copyFile = Path(root, "copy.bin")
            val bytes = byteArrayOf(0, 1, 2, 127, -128, -1)
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeBytes(sourceFile, bytes)
            SystemCoroutineFileSystem.writeBytes(sourceFile, bytes, append = true)

            assertContentEquals(bytes + bytes, SystemCoroutineFileSystem.readBytes(sourceFile))

            val copied = SystemCoroutineFileSystem.source(sourceFile).use { source ->
                SystemCoroutineFileSystem.sink(copyFile).use { sink ->
                    val copied = source.copyTo(sink)
                    sink.flush()
                    copied
                }
            }

            assertEquals((bytes.size * 2).toLong(), copied)
            assertContentEquals(bytes + bytes, SystemCoroutineFileSystem.readBytes(copyFile))
        }
    }
}
