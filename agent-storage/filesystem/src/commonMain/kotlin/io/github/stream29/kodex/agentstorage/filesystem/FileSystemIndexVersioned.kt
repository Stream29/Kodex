package io.github.stream29.kodex.agentstorage.filesystem

import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Direct filesystem projection of one sparse timeline.
 *
 * Each stored change point is one `<index>.json` file in [directory]. This
 * class owns neither the directory nor a process lease and intentionally keeps
 * no in-memory index or value cache. A rebuildable `latest.json` accelerates
 * exact tail reads without replacing the numbered files as the source of truth.
 *
 * A failed or cancelled revert compensates its in-process file moves. Process
 * interruption is not repaired when the timeline is opened.
 */
public class FileSystemIndexVersioned<T>(
    private val directory: Path,
    private val serializer: KSerializer<T>,
    private val json: Json,
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
) : MutableIndexVersioned<T> {
    override suspend fun latestIndex(): Int {
        latestIndexFromPointerOrNull()?.let { return it }
        val rebuilt = storedIndexes().lastOrNull() ?: EmptyIndex
        tryCreateLatestIndex(rebuilt)
        return rebuilt
    }

    override suspend fun get(index: Int): T {
        require(index >= 0) { "Index $index must be non-negative." }
        val storedIndex = floorToIndex(index)
            ?: throw IllegalArgumentException("No value is visible at index $index.")
        return getUnsafe(storedIndex)
    }

    override suspend fun floorToIndex(index: Int): Int? {
        if (index >= 0 && fileSystem.metadataOrNull(directory.entryPath(index))?.isRegularFile == true) {
            return index
        }
        return storedIndexes().binaryFloor(index)
    }

    override suspend fun ceilToIndex(index: Int): Int? {
        if (index >= 0 && fileSystem.metadataOrNull(directory.entryPath(index))?.isRegularFile == true) {
            return index
        }
        return storedIndexes().binaryCeil(index)
    }

    override suspend fun set(index: Int, value: T) {
        require(index >= 0) { "Index $index must be non-negative." }
        val latest = latestIndex()
        require(index > latest) {
            "Sparse append-only timeline requires index greater than $latest, got $index."
        }
        setUnsafe(index, value)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun revert(untilExclusive: Int) {
        require(untilExclusive >= 0) {
            "Revert boundary $untilExclusive must be non-negative."
        }
        val indexes = storedIndexes()
        val suffix = indexes.filter { index -> index >= untilExclusive }.asReversed()
        if (suffix.isEmpty()) return
        val previousLatest = indexes.last()
        val revertedLatest = indexes.lastOrNull { index -> index < untilExclusive } ?: EmptyIndex

        val operationId = Uuid.generateV7()
        val pending = Path(directory, ".kodex-revert-pending-$operationId")
        val committed = Path(directory, ".kodex-revert-committed-$operationId")
        fileSystem.createDirectories(pending, mustCreate = true)
        val moved = mutableListOf<Int>()
        try {
            for (index in suffix) {
                fileSystem.atomicMove(directory.entryPath(index), Path(pending, "$index.json"))
                moved += index
            }
            writeLatestIndex(revertedLatest)
            withContext(NonCancellable) {
                val cleanupDirectory = runCatching {
                    fileSystem.atomicMove(pending, committed)
                    committed
                }.getOrElse { pending }
                runCatching { deleteRecursively(fileSystem, cleanupDirectory) }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                for (index in moved.asReversed()) {
                    runCatching {
                        fileSystem.atomicMove(Path(pending, "$index.json"), directory.entryPath(index))
                    }.onFailure(failure::addSuppressed)
                }
                runCatching { writeLatestIndex(previousLatest) }
                    .onFailure(failure::addSuppressed)
                runCatching { fileSystem.delete(pending, mustExist = false) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    /** Lists stored change-point indexes in ascending order. */
    public suspend fun storedIndexes(): List<Int> {
        if (fileSystem.metadataOrNull(directory)?.isDirectory != true) return emptyList()
        return fileSystem.list(directory)
            .mapNotNull { path -> path.name.toStoredIndexOrNull() }
            .sorted()
    }

    /**
     * Reads exactly the entry at [index] without resolving a sparse timeline
     * floor. Callers must establish that [index] is a stored change point.
     */
    public suspend fun getUnsafe(index: Int): T {
        return json.decodeFromString(serializer, fileSystem.readString(directory.entryPath(index)))
    }

    /**
     * Writes exactly the entry at [index] without enforcing append-only order.
     * Callers must own the timeline and maintain its index invariants.
     */
    @OptIn(ExperimentalUuidApi::class)
    public suspend fun setUnsafe(index: Int, value: T) {
        require(index >= 0) { "Index $index must be non-negative." }
        fileSystem.createDirectories(directory)
        val previousLatest = latestIndex()
        val temporary = Path(directory, ".kodex-write-${Uuid.generateV7()}.tmp")
        try {
            fileSystem.writeString(
                temporary,
                json.encodeToString(serializer, value),
                mustCreate = true,
            )
            try {
                writeLatestIndex(index)
                fileSystem.atomicMove(temporary, directory.entryPath(index))
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { writeLatestIndex(previousLatest) }
                        .onFailure(failure::addSuppressed)
                }
                throw failure
            }
        } finally {
            withContext(NonCancellable) {
                fileSystem.delete(temporary, mustExist = false)
            }
        }
    }

    /**
     * Reconciles the rebuildable latest-index file with an exact tail already
     * established by an owning cache.
     *
     * The caller must own this timeline and prove that [index] is the greatest
     * stored index, or `-1` when the timeline is empty.
     */
    public suspend fun reconcileLatestIndexUnsafe(index: Int) {
        require(index >= EmptyIndex) { "Latest index $index must be at least -1." }
        require(
            index == EmptyIndex ||
                fileSystem.metadataOrNull(directory.entryPath(index))?.isRegularFile == true,
        ) {
            "Latest index file does not exist: ${directory.entryPath(index)}"
        }
        if (latestIndexFromPointerOrNull() != index) {
            writeLatestIndex(index)
        }
    }

    /**
     * Reads only the rebuildable latest-index pointer without enumerating or
     * repairing this timeline.
     *
     * Returns `null` when the pointer is missing, malformed, or dangling.
     */
    public suspend fun latestIndexFromPointerOrNull(): Int? {
        return try {
            val index = json.decodeFromString(
                Int.serializer(),
                fileSystem.readString(directory.latestIndexPath()),
            )
            when {
                index < EmptyIndex -> null
                index == EmptyIndex -> index
                fileSystem.metadataOrNull(directory.entryPath(index))?.isRegularFile == true -> index
                else -> null
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    private suspend fun tryCreateLatestIndex(index: Int) {
        try {
            fileSystem.writeString(
                directory.latestIndexPath(),
                json.encodeToString(Int.serializer(), index),
                mustCreate = true,
            )
        } catch (_: IOException) {
            // Another reader or the owning writer may have published it.
        }
    }

    private suspend fun writeLatestIndex(index: Int) {
        require(index >= EmptyIndex) { "Latest index $index must be at least -1." }
        writeAtomically(
            directory.latestIndexPath(),
            json.encodeToString(Int.serializer(), index),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun writeAtomically(destination: Path, content: String) {
        val temporary = Path(directory, ".kodex-write-${Uuid.generateV7()}.tmp")
        try {
            fileSystem.writeString(temporary, content, mustCreate = true)
            fileSystem.atomicMove(temporary, destination)
        } finally {
            withContext(NonCancellable) {
                fileSystem.delete(temporary, mustExist = false)
            }
        }
    }
}

private fun Path.entryPath(index: Int): Path = Path(this, "$index.json")

private fun Path.latestIndexPath(): Path = Path(this, LatestIndexFile)

private fun String.toStoredIndexOrNull(): Int? {
    if (!endsWith(".json")) return null
    val number = removeSuffix(".json")
    if (number.isEmpty() || number.any { character -> !character.isDigit() }) return null
    return number.toIntOrNull()?.takeIf { index -> index >= 0 }
}

private fun List<Int>.binaryFloor(index: Int): Int? {
    val result = binarySearch(index)
    return getOrNull(if (result >= 0) result else -result - 2)
}

private fun List<Int>.binaryCeil(index: Int): Int? {
    val result = binarySearch(index)
    return getOrNull(if (result >= 0) result else -result - 1)
}

private const val EmptyIndex: Int = -1
private const val LatestIndexFile: String = "latest.json"
