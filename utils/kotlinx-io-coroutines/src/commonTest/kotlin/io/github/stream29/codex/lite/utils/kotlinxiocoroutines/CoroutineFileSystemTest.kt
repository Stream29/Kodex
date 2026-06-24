package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
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
}
