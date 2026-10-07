package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.coroutines.CoroutineContext

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
) : FileSystemLease {
    // A value result avoids exception recovery/copying and a second background
    // failure authority. This is not a second resource owner.
    private val completion = CompletableDeferred<Result<Unit>>()
    private val publication = CompletableDeferred<Result<Unit>>()
    private val lifetime = ownerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        var primary: Throwable? = null
        var published = false
        try {
            currentCoroutineContext().ensureActive()
            // This actual resource child is attached to its owner before any
            // bytes are published, including dispatcher/promise handoff gaps.
            val heartbeat = FileSystemLeaseHeartbeat(pid, acquiredAt, acquiredAt + duration)
            fileSystem.writeString(
                lockPath,
                LeaseJson.encodeToString(FileSystemLeaseHeartbeat.serializer(), heartbeat),
                mustCreate = true,
            )
            currentCoroutineContext().ensureActive()
            published = true
            publication.complete(Result.success(Unit))
            while (isActive) {
                delay(duration / 3)
                if (!renew()) {
                    currentCoroutineContext().cancel()
                    return@launch
                }
            }
        } catch (cancellation: CancellationException) {
            // Explicit close and parent cancellation are ordinary release.
            // An independently cancelled IO operation is an observed failure.
            if (!published || currentCoroutineContext().isActive) primary = cancellation
            currentCoroutineContext().cancel(cancellation)
        } catch (failure: Throwable) {
            primary = failure
            currentCoroutineContext().cancel()
        } finally {
            currentCoroutineContext().cancelChildren()
            val cleanup = runCatching {
                leaseCleanup(timeout = LeaseCleanupTimeout) {
                    releaseFileSystemLease(lockPath, pid, acquiredAt, fileSystem)
                }
            }
            val cleanupFailure = cleanup.exceptionOrNull()
            if (primary != null && cleanupFailure != null && primary !== cleanupFailure) {
                primary.addSuppressed(cleanupFailure)
            }
            val result = primary?.let { Result.failure<Unit>(it) } ?: cleanup
            // Pre-publication cancellation is the factory's primary failure,
            // not an additional cleanup error when that factory closes the child.
            completion.complete(if (published) result else cleanup)
            // Failed acquisition waits for cleanup too, with its exact primary
            // error and any distinct suppressed release error.
            if (!published) publication.complete(result)
        }
    }
    override val coroutineContext: CoroutineContext = ownerScope.coroutineContext + lifetime

    override fun close() {
        lifetime.cancel()
    }

    override suspend fun closeAndJoin() {
        close()
        lifetime.join()
        completion.await().getOrThrow()
    }

    internal suspend fun awaitAcquired() {
        publication.await().getOrThrow()
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
    requireOwnerJob().ensureActive()
    currentCoroutineContext().ensureActive()

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
    val lease = RenewableFileSystemLease(
        lockPath = lockPath,
        acquiredAt = acquiredAt,
        pid = pid,
        fileSystem = fileSystem,
        ownerScope = this,
        duration = duration,
    )
    try {
        lease.awaitAcquired()
        currentCoroutineContext().ensureActive()
        requireOwnerJob().ensureActive()
        return lease
    } catch (failure: Throwable) {
        leaseCleanup(failure) { lease.closeAndJoin() }
        throw failure
    }
}
