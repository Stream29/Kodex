package io.github.stream29.kodex.agentstorage.filesystemlayout

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.io.files.Path

public actual suspend fun requireStorageLayout(
    storageDirectory: Path,
    timelineNames: Set<String>,
    fileSystem: CoroutineFileSystem,
) {
    requireDirectory(fileSystem, storageDirectory, "Storage")
    timelineNames.forEach { timelineName ->
        requireDirectory(
            fileSystem,
            timelineDirectory(storageDirectory, timelineName),
            "Timeline",
        )
    }
}

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

public actual suspend fun readRecord(
    timelineDirectory: Path,
    index: Int,
    fileSystem: CoroutineFileSystem,
    maxByteCount: Long,
): ByteArray = fileSystem.readBytes(recordPath(timelineDirectory, index), maxByteCount)

public actual suspend fun readRecordPrefix(
    timelineDirectory: Path,
    index: Int,
    byteCount: Long,
    fileSystem: CoroutineFileSystem,
): ByteArray {
    require(byteCount >= 0L) { "byteCount: $byteCount" }
    return fileSystem.useSource(recordPath(timelineDirectory, index)) { source ->
        val buffer = Buffer()
        var remaining = byteCount
        while (remaining > 0L) {
            val read = source.readAtMostTo(buffer, minOf(remaining, RecordPrefixSegmentByteCount))
            if (read == -1L) break
            remaining -= read
        }
        buffer.readByteArray()
    }
}

public actual suspend fun writeRecord(
    timelineDirectory: Path,
    index: Int,
    content: ByteArray,
    fileSystem: CoroutineFileSystem,
) {
    fileSystem.writeBytes(recordPath(timelineDirectory, index), content)
}

public actual suspend fun moveRecord(
    timelineDirectory: Path,
    sourceIndex: Int,
    targetIndex: Int,
    fileSystem: CoroutineFileSystem,
) {
    fileSystem.atomicMove(
        recordPath(timelineDirectory, sourceIndex),
        recordPath(timelineDirectory, targetIndex),
    )
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

public actual suspend fun readLatestIndex(
    timelineDirectory: Path,
    fileSystem: CoroutineFileSystem,
): Int {
    val path = latestIndexPath(timelineDirectory)
    val value = fileSystem.readString(path).trim().toIntOrNull()
        ?: throw IOException("Latest pointer is not an integer: $path")
    if (value < EmptyIndex) {
        throw IOException("Latest pointer must be at least -1: $path")
    }
    return value
}

public actual suspend fun writeLatestIndex(
    timelineDirectory: Path,
    index: Int,
    fileSystem: CoroutineFileSystem,
) {
    require(index >= EmptyIndex) { "Latest index must be at least -1: $index" }
    fileSystem.writeString(latestIndexPath(timelineDirectory), index.toString())
}

private suspend fun requireDirectory(
    fileSystem: CoroutineFileSystem,
    path: Path,
    label: String,
) {
    if (fileSystem.metadataOrNull(path)?.isDirectory != true) {
        throw IOException("$label directory does not exist: $path")
    }
}

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

private const val EmptyIndex: Int = -1
private const val RecordPrefixSegmentByteCount: Long = 8_192L
private const val InitialIndexCapacity: Int = 16
private const val JsonSuffix: String = ".json"
private const val LatestIndexFile: String = "latest.json"
