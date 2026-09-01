package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Duration

public suspend fun CoroutineScope.FileSystemReadLease(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration = DefaultOwnerDuration,
): FileSystemLease = SharedReadLeases.acquire(this, directory, fileSystem, duration)

private object SharedReadLeases {
    private val mutex = Mutex()
    private val owners = mutableMapOf<String, SharedReadOwner>()

    suspend fun acquire(
        ownerScope: CoroutineScope,
        directory: Path,
        fileSystem: CoroutineFileSystem,
        duration: Duration,
    ): FileSystemLease {
        require(duration.isPositive()) { "Lease duration must be positive." }
        fileSystem.createDirectories(directory)
        val key = fileSystem.resolve(directory).toString()
        val ownerJob = ownerScope.requireOwnerJob()

        return mutex.withLock {
            owners[key]?.let { existing ->
                if (existing.isActive) {
                    if (existing.ownerJob === ownerJob) {
                        existing.references += 1
                        return@withLock SharedReadLease(ownerScope, existing)
                    }
                    throw FileSystemLeaseInUseException(
                        "The process read lease for $directory belongs to another CoroutineScope.",
                    )
                }
                owners.remove(key)
                existing.lease.closeAndJoin()
            }

            val ownerPath = ownerPath(directory, ReadOwnerSuffix)
            val lease = withAcquisitionGuard(directory, fileSystem) {
                val writers = activeOwnerPaths(directory, fileSystem).filter(Path::isWriteOwner)
                if (writers.isNotEmpty()) throw ownersInUse(writers)
                ownerScope.acquireRenewableFileSystemLease(ownerPath, fileSystem, duration)
            }
            val owner = SharedReadOwner(
                key = key,
                ownerJob = ownerJob,
                lease = lease,
                references = 1,
            )
            owners[key] = owner
            SharedReadLease(ownerScope, owner)
        }
    }

    suspend fun release(owner: SharedReadOwner) {
        val lease = mutex.withLock {
            check(owner.references > 0) { "Read lease reference count is already zero." }
            owner.references -= 1
            if (owner.references != 0) return
            if (owners[owner.key] === owner) {
                owners.remove(owner.key)
            }
            owner.lease
        }
        lease.closeAndJoin()
    }
}

private class SharedReadOwner(
    val key: String,
    val ownerJob: Job,
    val lease: RenewableFileSystemLease,
    var references: Int,
) {
    val isActive: Boolean
        get() = lease.coroutineContext.job.isActive && references > 0
}

private class SharedReadLease(
    ownerScope: CoroutineScope,
    private val owner: SharedReadOwner,
) : FileSystemLease,
    CoroutineScope by ownerScope.supervisorChildScope() {
    init {
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    SharedReadLeases.release(owner)
                }
            }
        }
    }

    override fun close() {
        cancel()
    }
}
