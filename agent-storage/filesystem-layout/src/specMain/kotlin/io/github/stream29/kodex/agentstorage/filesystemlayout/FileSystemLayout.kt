package io.github.stream29.kodex.agentstorage.filesystemlayout

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public expect suspend fun requireStorageLayout(
    storageDirectory: Path,
    timelineNames: Set<String>,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
)

public expect fun timelineDirectory(storageDirectory: Path, timelineName: String): Path

public expect suspend fun storedRecordIndexes(
    timelineDirectory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): IntArray

public expect fun recordPath(timelineDirectory: Path, index: Int): Path

public expect suspend fun readRecord(
    timelineDirectory: Path,
    index: Int,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    maxByteCount: Long = Long.MAX_VALUE,
): ByteArray

public expect suspend fun readRecordPrefix(
    timelineDirectory: Path,
    index: Int,
    byteCount: Long,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): ByteArray

public expect suspend fun writeRecord(
    timelineDirectory: Path,
    index: Int,
    content: ByteArray,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
)

public expect suspend fun moveRecord(
    timelineDirectory: Path,
    sourceIndex: Int,
    targetIndex: Int,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
)

public expect suspend fun deleteRecord(
    timelineDirectory: Path,
    index: Int,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    mustExist: Boolean = true,
)

public expect fun latestIndexPath(timelineDirectory: Path): Path

public expect suspend fun readLatestIndex(
    timelineDirectory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Int

public expect suspend fun writeLatestIndex(
    timelineDirectory: Path,
    index: Int,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
)
