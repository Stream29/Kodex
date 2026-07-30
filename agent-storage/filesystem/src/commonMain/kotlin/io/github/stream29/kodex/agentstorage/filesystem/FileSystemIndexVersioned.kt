package io.github.stream29.kodex.agentstorage.filesystem

import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Direct filesystem projection of one sparse timeline.
 *
 * Each stored change point is one `<index>.json` file in [directory]. This
 * class owns neither the directory nor a process lease and intentionally keeps
 * no long-lived index or value cache.
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
    override suspend fun latestIndex(): Int =
        storedIndexes().lastOrNull() ?: -1

    override suspend fun get(index: Int): T {
        require(index >= 0) { "Index $index must be non-negative." }
        val storedIndex = floorToIndex(index)
            ?: throw IllegalArgumentException("No value is visible at index $index.")
        return getUnsafe(storedIndex)
    }

    override suspend fun floorToIndex(index: Int): Int? =
        storedIndexes().binaryFloor(index)

    override suspend fun ceilToIndex(index: Int): Int? =
        storedIndexes().binaryCeil(index)

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
        val suffix = storedIndexes().filter { index -> index >= untilExclusive }.asReversed()
        if (suffix.isEmpty()) return

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
            fileSystem.atomicMove(pending, committed)
            runCatching { deleteRecursively(fileSystem, committed) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                for (index in moved.asReversed()) {
                    runCatching {
                        fileSystem.atomicMove(Path(pending, "$index.json"), directory.entryPath(index))
                    }.onFailure(failure::addSuppressed)
                }
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
    public suspend fun setUnsafe(index: Int, value: T) {
        fileSystem.createDirectories(directory)
        writeAtomically(directory.entryPath(index), json.encodeToString(serializer, value))
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun writeAtomically(destination: Path, content: String) {
        val temporary = Path(directory, ".kodex-write-${Uuid.generateV7()}.tmp")
        try {
            fileSystem.writeString(temporary, content, mustCreate = true)
            fileSystem.atomicMove(temporary, destination)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }
}

private fun Path.entryPath(index: Int): Path = Path(this, "$index.json")

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
