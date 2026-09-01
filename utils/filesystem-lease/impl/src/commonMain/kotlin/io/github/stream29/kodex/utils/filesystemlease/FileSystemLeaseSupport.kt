package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal suspend fun FileSystemLease.closeAndJoin() {
    close()
    coroutineContext.job.join()
}

internal fun CoroutineScope.requireOwnerJob(): Job =
    requireNotNull(coroutineContext[Job]) {
        "A filesystem lease owner requires a CoroutineScope with a Job."
    }

internal suspend fun <T> withAcquisitionGuard(
    directory: Path,
    fileSystem: CoroutineFileSystem,
    block: suspend () -> T,
): T = coroutineScope {
    val guard = try {
        acquireRenewableFileSystemLease(
            lockPath = Path(directory, GuardFileName),
            fileSystem = fileSystem,
            duration = GuardDuration,
        )
    } catch (failure: FileSystemLeaseInUseException) {
        throw FileSystemLeaseInUseException(
            "Filesystem lease acquisition is already in progress for $directory.",
        )
    }
    try {
        block()
    } finally {
        withContext(NonCancellable) {
            guard.closeAndJoin()
        }
    }
}

internal suspend fun activeOwnerPaths(
    directory: Path,
    fileSystem: CoroutineFileSystem,
): List<Path> = buildList {
    fileSystem.list(directory)
        .asSequence()
        .filter { path -> path.name.isOwnerName() }
        .forEach { path ->
            val heartbeat = readHeartbeatOrNull(fileSystem, path) ?: return@forEach
            if (heartbeat.expiresAt <= Clock.System.now()) {
                removeStaleLease(path, fileSystem)
            } else {
                add(path)
            }
        }
}

@OptIn(ExperimentalUuidApi::class)
internal suspend fun removeStaleLease(
    path: Path,
    fileSystem: CoroutineFileSystem,
) {
    val quarantine = Path(path.parent!!, ".stale-lock-${Uuid.generateV7()}.json")
    fileSystem.atomicMove(path, quarantine)
    fileSystem.delete(quarantine, mustExist = false)
}

@OptIn(ExperimentalUuidApi::class)
internal suspend fun writeHeartbeat(
    fileSystem: CoroutineFileSystem,
    lockPath: Path,
    heartbeat: FileSystemLeaseHeartbeat,
) {
    val temporary = Path(lockPath.parent!!, ".lock-${Uuid.generateV7()}.tmp")
    try {
        fileSystem.writeString(
            temporary,
            LeaseJson.encodeToString(FileSystemLeaseHeartbeat.serializer(), heartbeat),
            mustCreate = true,
        )
        fileSystem.atomicMove(temporary, lockPath)
    } finally {
        withContext(NonCancellable) {
            fileSystem.delete(temporary, mustExist = false)
        }
    }
}

internal suspend fun releaseFileSystemLease(
    lockPath: Path,
    pid: Long,
    acquiredAt: Instant,
    fileSystem: CoroutineFileSystem,
) {
    if (readHeartbeatOrNull(fileSystem, lockPath)?.matches(pid, acquiredAt) == true) {
        fileSystem.delete(lockPath, mustExist = false)
    }
}

internal suspend fun readHeartbeatOrNull(
    fileSystem: CoroutineFileSystem,
    path: Path,
): FileSystemLeaseHeartbeat? {
    if (fileSystem.metadataOrNull(path) == null) return null
    return try {
        LeaseJson.decodeFromString(
            FileSystemLeaseHeartbeat.serializer(),
            fileSystem.readString(path),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    }
}

internal fun ownerPath(directory: Path, suffix: String): Path =
    Path(directory, "${processId()}$suffix")

internal fun Path.isReadOwner(): Boolean = name.endsWith(ReadOwnerSuffix)

internal fun Path.isWriteOwner(): Boolean = name.endsWith(WriteOwnerSuffix)

internal fun ownersInUse(paths: List<Path>): FileSystemLeaseInUseException =
    FileSystemLeaseInUseException("Filesystem lease is in use: ${paths.joinToString()}")

internal fun FileSystemLeaseHeartbeat.matches(
    pid: Long,
    acquiredAt: Instant,
): Boolean = this.pid == pid && this.acquiredAt == acquiredAt

private fun String.isOwnerName(): Boolean =
    endsWith(ReadOwnerSuffix) && removeSuffix(ReadOwnerSuffix).toLongOrNull() != null ||
        endsWith(WriteOwnerSuffix) && removeSuffix(WriteOwnerSuffix).toLongOrNull() != null

@Serializable
internal data class FileSystemLeaseHeartbeat(
    val pid: Long,
    val acquiredAt: Instant,
    val expiresAt: Instant,
)

internal val LeaseJson: Json = Json
internal val DefaultOwnerDuration: Duration = 30.seconds
internal val GuardDuration: Duration = 10.seconds
internal val ReaderPollInterval: Duration = 25.milliseconds
internal const val GuardFileName: String = "guard.lock"
internal const val ReadOwnerSuffix: String = ".read.lock"
internal const val WriteOwnerSuffix: String = ".write.lock"
