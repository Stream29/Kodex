package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoroutineFileSystemTest {
    @Test
    fun readsWritesListsAndDeletesFiles() = runTest {
        val root = Path(SystemTemporaryDirectory, "codex-lite-coroutine-fs-${Random.nextLong()}")
        val child = Path(root, "dir/file.txt")
        try {
            SystemCoroutineFileSystem.createDirectories(child.parent!!)
            SystemCoroutineFileSystem.writeString(child, "hello\n")
            SystemCoroutineFileSystem.writeString(child, "world\n", append = true)

            assertTrue(SystemCoroutineFileSystem.exists(child))
            assertEquals("hello\nworld\n", SystemCoroutineFileSystem.readString(child))
            assertTrue(SystemCoroutineFileSystem.metadataOrNull(child)?.isRegularFile == true)
            assertEquals(listOf(child), SystemCoroutineFileSystem.list(child.parent!!))

            SystemCoroutineFileSystem.delete(child)
            assertFalse(SystemCoroutineFileSystem.exists(child))
        } finally {
            SystemCoroutineFileSystem.delete(Path(root, "dir"), mustExist = false)
            SystemCoroutineFileSystem.delete(root, mustExist = false)
        }
    }

    @Test
    fun readsWritesAndCopiesBinaryFiles() = runTest {
        val root = Path(SystemTemporaryDirectory, "codex-lite-coroutine-fs-binary-${Random.nextLong()}")
        val sourceFile = Path(root, "source.bin")
        val copyFile = Path(root, "copy.bin")
        val bytes = byteArrayOf(0, 1, 2, 127, -128, -1)
        try {
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
        } finally {
            SystemCoroutineFileSystem.delete(sourceFile, mustExist = false)
            SystemCoroutineFileSystem.delete(copyFile, mustExist = false)
            SystemCoroutineFileSystem.delete(root, mustExist = false)
        }
    }
}
