package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.files.Path

public actual suspend fun CoroutineScope.FileSystemWriteLease(
    directory: Path,
    fileSystem: CoroutineFileSystem,
    duration: Duration,
): FileSystemLease {
    require(duration.isPositive()) { "Lease duration must be positive." }
    requireOwnerJob().ensureActive()
    fileSystem.createDirectories(directory)

    val ownerPath = ownerPath(directory, WriteOwnerSuffix)
    var acquired: FileSystemLease? = null
    try {
        val lease = withAcquisitionGuard(directory, fileSystem) {
            val existing = activeOwnerPaths(directory, fileSystem)
            if (existing.isNotEmpty()) throw ownersInUse(existing)
            acquireRenewableFileSystemLease(ownerPath, fileSystem, duration).also { acquired = it }
        }
        currentCoroutineContext().ensureActive()
        requireOwnerJob().ensureActive()
        return lease
    } catch (failure: Throwable) {
        leaseCleanup(failure) { acquired?.closeAndJoin() }
        throw failure
    }
}
