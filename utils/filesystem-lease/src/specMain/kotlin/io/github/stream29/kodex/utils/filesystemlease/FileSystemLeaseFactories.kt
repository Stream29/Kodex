package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public expect suspend fun CoroutineScope.FileSystemLease(
    lockPath: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration,
): FileSystemLease

public expect suspend fun CoroutineScope.FileSystemReadLease(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration = DefaultOwnerDuration,
): FileSystemLease

public expect suspend fun CoroutineScope.FileSystemWriteLease(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration = DefaultOwnerDuration,
    waitForReaders: Duration = Duration.ZERO,
): FileSystemLease

internal val DefaultOwnerDuration: Duration = 30.seconds
