package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

public suspend fun CoroutineScope.FileSystemLease(
    lockPath: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration,
): FileSystemLease = acquireRenewableFileSystemLease(lockPath, fileSystem, duration)

internal class RenewableFileSystemLease(
    private val lockPath: Path,
    private val acquiredAt: Instant,
    private val pid: Long,
    private val fileSystem: CoroutineFileSystem,
    ownerScope: CoroutineScope,
    private val duration: Duration,
) : FileSystemLease,
    CoroutineScope by ownerScope.supervisorChildScope() {
    init {
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (isActive) {
                    delay(duration / 3)
                    if (!renew()) return@launch
                }
            } finally {
                withContext(NonCancellable) {
                    releaseFileSystemLease(lockPath, pid, acquiredAt, fileSystem)
                }
            }
        }
    }

    override fun close() {
        cancel()
    }

    private suspend fun renew(): Boolean {
        val heartbeat = readHeartbeatOrNull(fileSystem, lockPath) ?: return false
        if (!heartbeat.matches(pid, acquiredAt)) return false
        writeHeartbeat(
            fileSystem = fileSystem,
            lockPath = lockPath,
            heartbeat = heartbeat.copy(expiresAt = Clock.System.now() + duration),
        )
        return true
    }
}

internal suspend fun CoroutineScope.acquireRenewableFileSystemLease(
    lockPath: Path,
    fileSystem: CoroutineFileSystem,
    duration: Duration,
): RenewableFileSystemLease {
    require(duration.isPositive()) { "Lease duration must be positive." }
    requireOwnerJob()

    readHeartbeatOrNull(fileSystem, lockPath)?.let { heartbeat ->
        if (heartbeat.expiresAt > Clock.System.now()) {
            throw FileSystemLeaseInUseException(
                "Lease $lockPath is owned by process ${heartbeat.pid} until ${heartbeat.expiresAt}.",
            )
        }
        removeStaleLease(lockPath, fileSystem)
    }

    val acquiredAt = Clock.System.now()
    val pid = processId()
    val heartbeat = FileSystemLeaseHeartbeat(
        pid = pid,
        acquiredAt = acquiredAt,
        expiresAt = acquiredAt + duration,
    )
    fileSystem.writeString(
        lockPath,
        LeaseJson.encodeToString(FileSystemLeaseHeartbeat.serializer(), heartbeat),
        mustCreate = true,
    )
    return RenewableFileSystemLease(
        lockPath = lockPath,
        acquiredAt = acquiredAt,
        pid = pid,
        fileSystem = fileSystem,
        ownerScope = this,
        duration = duration,
    )
}
