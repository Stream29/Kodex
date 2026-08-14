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

private fun timeline(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): FileSystemIndexVersioned<Int> =
    FileSystemIndexVersioned(
        directory = directory,
        serializer = Int.serializer(),
        json = OpenAiJsonCodec,
        fileSystem = fileSystem,
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

        test("uses the latest file and exact indexes without listing") { directory ->
            val fileSystem = CountingListFileSystem()
            val storage = timeline(directory, fileSystem)
            storage[0] = 10
            storage[3] = 30
            fileSystem.reset()

            val reopened = timeline(directory, fileSystem)

            assertEquals(3, reopened.latestIndex())
            assertEquals(3, reopened.floorToIndex(3))
            assertEquals(3, reopened.ceilToIndex(3))
            assertEquals(30, reopened[3])
            assertEquals(0, fileSystem.listCalls)
        }

        test("rebuilds a missing latest file once") { directory ->
            val storage = timeline(directory)
            storage[0] = 10
            storage[3] = 30
            SystemCoroutineFileSystem.delete(Path(directory, "latest.json"))
            val fileSystem = CountingListFileSystem()
            val reopened = timeline(directory, fileSystem)

            assertEquals(3, reopened.latestIndex())
            assertEquals(1, fileSystem.listCalls)
            assertEquals("3", SystemCoroutineFileSystem.readString(Path(directory, "latest.json")))

            fileSystem.reset()
            assertEquals(3, reopened.latestIndex())
            assertEquals(0, fileSystem.listCalls)
        }

        test("falls back from a dangling latest file") { directory ->
            val storage = timeline(directory)
            storage[0] = 10
            storage[3] = 30
            SystemCoroutineFileSystem.writeString(Path(directory, "latest.json"), "8")
            val fileSystem = CountingListFileSystem()
            val reopened = timeline(directory, fileSystem)

            assertEquals(3, reopened.latestIndex())
            assertEquals(1, fileSystem.listCalls)

            reopened.reconcileLatestIndexUnsafe(3)
            fileSystem.reset()
            assertEquals(3, reopened.latestIndex())
            assertEquals(0, fileSystem.listCalls)
        }

        test("falls back from a malformed latest file") { directory ->
            val storage = timeline(directory)
            storage[0] = 10
            storage[3] = 30
            SystemCoroutineFileSystem.writeString(Path(directory, "latest.json"), "not-json")
            val fileSystem = CountingListFileSystem()
            val reopened = timeline(directory, fileSystem)

            assertEquals(3, reopened.latestIndex())
            assertEquals(1, fileSystem.listCalls)

            reopened.reconcileLatestIndexUnsafe(3)
            fileSystem.reset()
            assertEquals(3, reopened.latestIndex())
            assertEquals(0, fileSystem.listCalls)
        }

        test("publishes latest before its numbered entry") { directory ->
            val fileSystem = RecordingMoveFileSystem()
            val storage = timeline(directory, fileSystem)

            storage[3] = 30

            assertEquals(listOf("latest.json", "3.json"), fileSystem.destinations.takeLast(2))
        }

        test("restores latest when numbered entry publication fails") { directory ->
            val original = timeline(directory)
            original[0] = 10
            val failing = timeline(directory, FailingDestinationMoveFileSystem("3.json"))

            assertFails { failing[3] = 30 }

            assertEquals(0, original.latestIndex())
            assertEquals("0", SystemCoroutineFileSystem.readString(Path(directory, "latest.json")))
            assertFalse(SystemCoroutineFileSystem.exists(Path(directory, "3.json")))
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

private class CountingListFileSystem(
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    var listCalls: Int = 0
        private set

    override suspend fun list(directory: Path): Collection<Path> {
        listCalls += 1
        return delegate.list(directory)
    }

    fun reset() {
        listCalls = 0
    }
}

private class RecordingMoveFileSystem(
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    val destinations: MutableList<String> = mutableListOf()

    override suspend fun atomicMove(source: Path, destination: Path) {
        destinations += destination.name
        delegate.atomicMove(source, destination)
    }
}

private class FailingDestinationMoveFileSystem(
    private val destinationName: String,
    private val delegate: CoroutineFileSystem = SystemCoroutineFileSystem,
) : CoroutineFileSystem by delegate {
    private var shouldFail: Boolean = true

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (shouldFail && destination.name == destinationName) {
            shouldFail = false
            error("Injected atomic move failure.")
        }
        delegate.atomicMove(source, destination)
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
