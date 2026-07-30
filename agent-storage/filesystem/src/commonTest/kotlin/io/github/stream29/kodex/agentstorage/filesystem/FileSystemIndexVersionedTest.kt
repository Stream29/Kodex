package io.github.stream29.kodex.agentstorage.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNull

private suspend fun temporaryTimelineDirectory(): Path =
    Path(SystemTemporaryDirectory, "kodex-timeline-${Random.nextLong()}").also { directory ->
        SystemCoroutineFileSystem.createDirectories(directory)
    }

private fun timeline(directory: Path): FileSystemIndexVersioned<Int> =
    FileSystemIndexVersioned(
        directory = directory,
        serializer = Int.serializer(),
        json = OpenAiJsonCodec,
    )

val fileSystemIndexVersionedTest by testSuite {
    testFixture { temporaryTimelineDirectory() } closeWith {
        deleteRecursively(SystemCoroutineFileSystem, this)
    } asParameterForEach {
        test("persists sparse values and reverts a suffix") { directory ->
            val original = timeline(directory)
            original[0] = 10
            original[3] = 30
            original[8] = 80

            val reopened = timeline(directory)
            assertEquals(8, reopened.latestIndex())
            assertEquals(30, reopened[7])
            assertEquals(3, reopened.floorToIndex(7))
            assertEquals(8, reopened.ceilToIndex(7))
            assertNull(reopened.floorToIndex(-1))
            assertNull(reopened.ceilToIndex(9))

            reopened.revert(untilExclusive = 3)
            assertEquals(0, reopened.latestIndex())
            assertEquals(10, reopened[100])
            assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "3.json")))
            assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "8.json")))
        }

        test("provides exact access for an owning cache") { directory ->
            val storage = timeline(directory)
            storage.setUnsafe(3, 30)

            assertEquals(listOf(3), storage.storedIndexes())
            assertEquals(30, storage.getUnsafe(3))
            assertEquals(30, storage[5])
        }

        test("compensates a failed revert") { directory ->
            val original = timeline(directory)
            original[0] = 10
            original[3] = 30
            original[8] = 80
            val failing = FileSystemIndexVersioned(
                directory = directory,
                serializer = Int.serializer(),
                json = OpenAiJsonCodec,
                fileSystem = FailingMoveFileSystem("3.json"),
            )

            assertFails { failing.revert(untilExclusive = 3) }

            assertEquals(8, original.latestIndex())
            assertEquals(30, original[3])
            assertEquals(80, original[8])
        }

    }
}

private class FailingMoveFileSystem(
    private val sourceName: String,
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    private var shouldFail: Boolean = true

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (shouldFail && source.name == sourceName) {
            shouldFail = false
            error("Injected atomic move failure.")
        }
        delegate.atomicMove(source, destination)
    }
}
