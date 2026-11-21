package io.github.stream29.kodex.agentstorage.filesystemlayout

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

public actual fun timelineDirectory(
    storageDirectory: Path,
    timelineName: String,
): Path = Path(storageDirectory, timelineName)

public actual suspend fun storedRecordIndexes(
    timelineDirectory: Path,
    fileSystem: CoroutineFileSystem,
): IntArray {
    val indexes = IntArrayBuilder()
    fileSystem.list(timelineDirectory).forEach { path ->
        path.name.toStoredIndexOrNull()?.let(indexes::add)
    }
    return indexes.toArray().also(IntArray::sort)
}

public actual fun recordPath(
    timelineDirectory: Path,
    index: Int,
): Path {
    require(index >= 0) { "Record index must be non-negative: $index" }
    return Path(timelineDirectory, "$index.json")
}

public actual suspend fun deleteRecord(
    timelineDirectory: Path,
    index: Int,
    fileSystem: CoroutineFileSystem,
    mustExist: Boolean,
) {
    fileSystem.delete(recordPath(timelineDirectory, index), mustExist)
}

public actual fun latestIndexPath(timelineDirectory: Path): Path =
    Path(timelineDirectory, LatestIndexFile)

private fun String.toStoredIndexOrNull(): Int? {
    if (!endsWith(JsonSuffix)) return null
    val number = removeSuffix(JsonSuffix)
    val index = number.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return index.takeIf { it.toString() == number }
}

private class IntArrayBuilder {
    private var values: IntArray = IntArray(InitialIndexCapacity)
    private var size: Int = 0

    fun add(value: Int) {
        if (size == values.size) {
            values = values.copyOf(values.size * 2)
        }
        values[size] = value
        size += 1
    }

    fun toArray(): IntArray = values.copyOf(size)
}

private const val InitialIndexCapacity: Int = 16
private const val JsonSuffix: String = ".json"
private const val LatestIndexFile: String = "latest.json"
