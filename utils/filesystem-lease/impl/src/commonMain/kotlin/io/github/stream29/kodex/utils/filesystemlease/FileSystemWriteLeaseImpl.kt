package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration

public suspend fun CoroutineScope.FileSystemWriteLease(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration = DefaultOwnerDuration,
    waitForReaders: Duration = Duration.ZERO,
): FileSystemLease {
    require(duration.isPositive()) { "Lease duration must be positive." }
    require(!waitForReaders.isNegative()) { "Reader wait duration must not be negative." }
    fileSystem.createDirectories(directory)

    val ownerPath = ownerPath(directory, WriteOwnerSuffix)
    val lease = withAcquisitionGuard(directory, fileSystem) {
        val writers = activeOwnerPaths(directory, fileSystem).filter(Path::isWriteOwner)
        if (writers.isNotEmpty()) throw ownersInUse(writers)
        acquireRenewableFileSystemLease(ownerPath, fileSystem, duration)
    }
    try {
        val deadline = Clock.System.now() + waitForReaders
        while (true) {
            val readers = withAcquisitionGuard(directory, fileSystem) {
                activeOwnerPaths(directory, fileSystem).filter(Path::isReadOwner)
            }
            if (readers.isEmpty()) return lease
            if (Clock.System.now() >= deadline) throw ownersInUse(readers)
            delay(ReaderPollInterval)
        }
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            lease.closeAndJoin()
        }
        throw failure
    }
}
