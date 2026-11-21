package io.github.stream29.kodex.agentstorage.filesystemlayout

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path

public expect fun timelineDirectory(storageDirectory: Path, timelineName: String): Path

public expect suspend fun storedRecordIndexes(
    timelineDirectory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): IntArray

public expect fun recordPath(timelineDirectory: Path, index: Int): Path

public expect suspend fun deleteRecord(
    timelineDirectory: Path,
    index: Int,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    mustExist: Boolean = true,
)

public expect fun latestIndexPath(timelineDirectory: Path): Path
