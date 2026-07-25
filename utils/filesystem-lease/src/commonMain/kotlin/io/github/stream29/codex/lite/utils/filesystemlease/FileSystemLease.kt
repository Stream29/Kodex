package io.github.stream29.codex.lite.utils.filesystemlease

import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.osenvironment.processId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
private data class LeaseHeartbeat(
    val pid: Long,
    val acquiredAt: Instant,
    val expiresAt: Instant,
)

public class FileSystemLeaseInUseException(
    public val ownerPid: Long,
    public val expiresAt: Instant,
) : IllegalStateException("Lease is owned by process $ownerPid until $expiresAt.")

/** Renewable filesystem lease stored in the resource's lock file. */
public class FileSystemLease internal constructor(
    private val lockPath: Path,
    private val acquiredAt: Instant,
    private val pid: Long,
    private val fileSystem: CoroutineFileSystem,
    parentScope: CoroutineScope,
    private val duration: Duration,
) : AutoCloseable,
    CoroutineScope by CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    ) {
    init {
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (isActive) {
                    delay(duration / 3)
                    if (!renew()) return@launch
                }
            } finally {
                withContext(NonCancellable) { releaseLock() }
            }
        }
    }

    override fun close() {
        cancel()
    }

    private suspend fun renew(): Boolean {
        val heartbeat = readHeartbeatOrNull(fileSystem, lockPath) ?: return false
        if (!heartbeat.matchesLease(pid, acquiredAt)) {
            return false
        }
        writeHeartbeat(heartbeat.copy(expiresAt = Clock.System.now() + duration))
        return true
    }

    private suspend fun releaseLock() {
        if (readHeartbeatOrNull(fileSystem, lockPath)?.matchesLease(pid, acquiredAt) == true) {
            fileSystem.delete(lockPath, mustExist = false)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun writeHeartbeat(heartbeat: LeaseHeartbeat) {
        val temporary = Path(lockPath.parent!!, ".lock-${Uuid.generateV7()}.tmp")
        try {
            fileSystem.writeString(
                temporary,
                LeaseJson.encodeToString(LeaseHeartbeat.serializer(), heartbeat),
                mustCreate = true,
            )
            fileSystem.atomicMove(temporary, lockPath)
        } finally {
            withContext(NonCancellable) {
                fileSystem.delete(temporary, mustExist = false)
            }
        }
    }

}

@OptIn(ExperimentalUuidApi::class)
public suspend fun CoroutineScope.FileSystemLease(
    lockPath: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration,
): FileSystemLease {
    require(duration.isPositive()) { "Lease duration must be positive." }
    val observedHeartbeat = readHeartbeatOrNull(fileSystem, lockPath)
    if (observedHeartbeat != null) {
        val now = Clock.System.now()
        if (observedHeartbeat.expiresAt > now) {
            throw FileSystemLeaseInUseException(
                ownerPid = observedHeartbeat.pid,
                expiresAt = observedHeartbeat.expiresAt,
            )
        }
        val quarantine = Path(lockPath.parent!!, ".stale-lock-${Uuid.generateV7()}.json")
        fileSystem.atomicMove(lockPath, quarantine)
        fileSystem.delete(quarantine, mustExist = false)
    }

    val acquiredAt = Clock.System.now()
    val pid = processId()
    val heartbeat = LeaseHeartbeat(
        pid = pid,
        acquiredAt = acquiredAt,
        expiresAt = acquiredAt + duration,
    )
    fileSystem.writeString(
        lockPath,
        LeaseJson.encodeToString(LeaseHeartbeat.serializer(), heartbeat),
        mustCreate = true,
    )
    return FileSystemLease(
        lockPath = lockPath,
        acquiredAt = acquiredAt,
        pid = pid,
        fileSystem = fileSystem,
        parentScope = this,
        duration = duration,
    )
}

private fun LeaseHeartbeat.matchesLease(
    pid: Long,
    acquiredAt: Instant,
): Boolean = this.pid == pid && this.acquiredAt == acquiredAt

private suspend fun readHeartbeatOrNull(
    fileSystem: CoroutineFileSystem,
    path: Path,
): LeaseHeartbeat? {
    if (fileSystem.metadataOrNull(path) == null) return null
    return LeaseJson.decodeFromString(
        LeaseHeartbeat.serializer(),
        fileSystem.readString(path),
    )
}

private val LeaseJson: Json = Json
