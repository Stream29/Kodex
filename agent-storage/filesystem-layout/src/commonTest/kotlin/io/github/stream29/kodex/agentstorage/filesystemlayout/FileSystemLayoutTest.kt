package io.github.stream29.kodex.agentstorage.filesystemlayout

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val OldTimelineNames = setOf(
    "compaction",
    "settings",
    "timestamp",
    "token-count",
    "stable",
    "unstable",
)

private val CurrentTimelineNames = setOf(
    "index",
    "work",
    "settings",
    "timestamp",
    "token-count",
    "unstable",
)

private suspend fun temporaryDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-storage-layout-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

public val fileSystemLayoutTest by testSuite {
    testFixture { temporaryDirectory() } closeWith {
        deleteRecursively(this)
    } asParameterForEach {
        test("opens different historical timeline sets") { root ->
            val oldDirectory = Path(root, "old")
            createStorageDirectory(oldDirectory, OldTimelineNames)
            val currentDirectory = Path(root, "current")
            createStorageDirectory(currentDirectory, CurrentTimelineNames)

            requireStorageLayout(oldDirectory, OldTimelineNames)
            requireStorageLayout(currentDirectory, CurrentTimelineNames)
        }

        test("lists only canonical numeric records") { root ->
            createStorageDirectory(root, CurrentTimelineNames)
            val timeline = timelineDirectory(root, "index")
            SystemCoroutineFileSystem.writeString(recordPath(timeline, 12), "twelve")
            SystemCoroutineFileSystem.writeString(recordPath(timeline, 2), "two")
            SystemCoroutineFileSystem.writeString(Path(timeline, "02.json"), "unknown")
            SystemCoroutineFileSystem.writeString(Path(timeline, "latest.json"), "12")
            SystemCoroutineFileSystem.writeString(Path(timeline, "note.txt"), "unknown")

            assertContentEquals(intArrayOf(2, 12), storedRecordIndexes(timeline))
            assertTrue(SystemCoroutineFileSystem.exists(Path(timeline, "02.json")))
            assertTrue(SystemCoroutineFileSystem.exists(Path(timeline, "note.txt")))
        }

        test("performs raw record and latest operations") { root ->
            createStorageDirectory(root, CurrentTimelineNames)
            val timeline = timelineDirectory(root, "work")
            val payload = byteArrayOf(1, 2, 3, 4)

            writeRecord(timeline, 3, payload)
            assertContentEquals(payload, readRecord(timeline, 3))

            moveRecord(timeline, 3, 5)
            assertFalse(SystemCoroutineFileSystem.exists(recordPath(timeline, 3)))
            assertContentEquals(payload, readRecord(timeline, 5))

            writeLatestIndex(timeline, 5)
            assertEquals(5, readLatestIndex(timeline))

            deleteRecord(timeline, 5)
            assertFalse(SystemCoroutineFileSystem.exists(recordPath(timeline, 5)))
        }

        test("rejects missing declared timeline") { root ->
            SystemCoroutineFileSystem.createDirectories(Path(root, "index"))

            assertFailsWith<Exception> {
                requireStorageLayout(root, CurrentTimelineNames)
            }
        }
    }
}

private suspend fun createStorageDirectory(directory: Path, timelines: Set<String>) {
    SystemCoroutineFileSystem.createDirectories(directory)
    timelines.forEach { name ->
        SystemCoroutineFileSystem.createDirectories(Path(directory, name))
    }
}

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
