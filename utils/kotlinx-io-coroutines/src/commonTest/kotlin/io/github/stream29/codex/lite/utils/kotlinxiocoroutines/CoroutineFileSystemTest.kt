package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import de.infix.testBalloon.framework.core.testSuite

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
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
            val directory = Path(root, "dir")
            val child = Path(directory, "file.txt")
            SystemCoroutineFileSystem.createDirectories(directory)
            SystemCoroutineFileSystem.writeString(child, "hello\n")
            SystemCoroutineFileSystem.writeString(child, "world\n", append = true)

            assertTrue(SystemCoroutineFileSystem.exists(child))
            assertEquals("hello\nworld\n", SystemCoroutineFileSystem.readString(child))
            assertEquals(true, SystemCoroutineFileSystem.metadataOrNull(child)?.isRegularFile)
            assertEquals(listOf(child), SystemCoroutineFileSystem.list(directory))

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

        test("preserves Unicode paths and UTF-8 contents") { root ->
            val directory = Path(root, "目录-Русский-日本語")
            val sourceFile = Path(directory, "源文件-данные.txt")
            val destinationFile = Path(directory, "已移动-итог.txt")
            val content = "中文 / русский / 日本語 / cafe\u0301\n"

            SystemCoroutineFileSystem.createDirectories(directory)
            SystemCoroutineFileSystem.writeString(sourceFile, content)

            assertTrue(SystemCoroutineFileSystem.exists(sourceFile))
            assertEquals(content, SystemCoroutineFileSystem.readString(sourceFile))
            assertEquals(listOf(sourceFile), SystemCoroutineFileSystem.list(directory))
            assertEquals(content, SystemCoroutineFileSystem.readString(SystemCoroutineFileSystem.resolve(sourceFile)))

            SystemCoroutineFileSystem.atomicMove(sourceFile, destinationFile)

            assertFalse(SystemCoroutineFileSystem.exists(sourceFile))
            assertEquals(content, SystemCoroutineFileSystem.readString(destinationFile))
            assertEquals(listOf(destinationFile), SystemCoroutineFileSystem.list(directory))
        }

        test("mustCreate publishes exactly one file") { root ->
            val file = Path(root, "exclusive.txt")
            SystemCoroutineFileSystem.createDirectories(root)

            SystemCoroutineFileSystem.writeString(file, "first", mustCreate = true)

            assertFails {
                SystemCoroutineFileSystem.writeString(file, "second", mustCreate = true)
            }
            assertEquals("first", SystemCoroutineFileSystem.readString(file))
        }
    }
}
