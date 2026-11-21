package io.github.stream29.kodex.agentstorage.filesystemlayout

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun temporaryDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-storage-layout-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

public val fileSystemLayoutTest by testSuite {
    testFixture { temporaryDirectory() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("lists only canonical numeric records") { root ->
            val timeline = timelineDirectory(root, "index")
            SystemCoroutineFileSystem.createDirectories(timeline)
            SystemCoroutineFileSystem.writeString(recordPath(timeline, 12), "twelve")
            SystemCoroutineFileSystem.writeString(recordPath(timeline, 2), "two")
            SystemCoroutineFileSystem.writeString(Path(timeline, "02.json"), "unknown")
            SystemCoroutineFileSystem.writeString(Path(timeline, "latest.json"), "12")
            SystemCoroutineFileSystem.writeString(Path(timeline, "note.txt"), "unknown")

            assertContentEquals(intArrayOf(2, 12), storedRecordIndexes(timeline))
            assertTrue(SystemCoroutineFileSystem.exists(Path(timeline, "02.json")))
            assertTrue(SystemCoroutineFileSystem.exists(Path(timeline, "note.txt")))
        }

        test("deletes only the requested record and keeps the latest pointer") { root ->
            val timeline = timelineDirectory(root, "work")
            SystemCoroutineFileSystem.createDirectories(timeline)
            SystemCoroutineFileSystem.writeString(recordPath(timeline, 3), "three")
            SystemCoroutineFileSystem.writeString(recordPath(timeline, 2), "two")
            SystemCoroutineFileSystem.writeString(latestIndexPath(timeline), "3")

            deleteRecord(timeline, 3)
            assertFalse(SystemCoroutineFileSystem.exists(recordPath(timeline, 3)))
            assertEquals("two", SystemCoroutineFileSystem.readString(recordPath(timeline, 2)))
            assertEquals("3", SystemCoroutineFileSystem.readString(latestIndexPath(timeline)))
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
