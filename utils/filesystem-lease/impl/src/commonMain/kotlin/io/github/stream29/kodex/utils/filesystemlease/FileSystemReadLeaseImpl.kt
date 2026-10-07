package io.github.stream29.kodex.utils.filesystemlease

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public suspend fun CoroutineScope.FileSystemReadLease(
    directory: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    duration: Duration = DefaultOwnerDuration,
): FileSystemLease = SharedReadLeases.acquire(this, directory, fileSystem, duration)

@OptIn(ExperimentalAtomicApi::class)
private object SharedReadLeases {
    // One atomic snapshot linearizes pins, owner identity and reference counts.
    // Transforms are memory-only and side-effect-free: neither acquisition IO
    // nor a cleanup deadline can hold up or abandon a reference decrement.
    private val inventory = AtomicReference(SharedReadInventory())

    private fun <T> accounting(
        transform: (SharedReadInventory) -> Pair<SharedReadInventory, T>,
    ): T {
        while (true) {
            val previous = inventory.load()
            val (next, result) = transform(previous)
            if (inventory.compareAndSet(previous, next)) return result
        }
    }

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
        ownerJob.ensureActive()

        val entry = accounting { state ->
            val slot = state.entries[key] ?: SharedReadSlot(SharedReadEntry(key))
            state.copy(entries = state.entries + (key to slot.copy(acquisitions = slot.acquisitions + 1))) to
                slot.entry
        }
        try {
            val handle = entry.acquisition.withLock {
                currentCoroutineContext().ensureActive()
                ownerJob.ensureActive()
                val (existing, shared) = accounting { state ->
                    val existing = state.entries.getValue(key).owner
                    val references = existing?.let { state.references[it] } ?: 0
                    if (existing != null && references > 0 && existing.lease.isActive) {
                        if (existing.ownerJob !== ownerJob) {
                            throw FileSystemLeaseInUseException(
                                "The process read lease for $directory belongs to another CoroutineScope.",
                            )
                        }
                        state.copy(references = state.references + (existing to references + 1)) to
                            (existing to true)
                    } else {
                        state to (existing to false)
                    }
                }
                // A reserved reference gets its handle even if the owner now
                // cancels; that handle's one finally still decrements it.
                if (shared) {
                    SharedReadLease(ownerScope, checkNotNull(existing))
                } else {
                    // A zero-reference or lost owner stays visible while retiring.
                    // Never publish a new owner at its path before cleanup ends.
                    existing?.lease?.closeAndJoin()
                    val ownerPath = ownerPath(directory, ReadOwnerSuffix)
                    var acquired: RenewableFileSystemLease? = null
                    val lease = try {
                        withAcquisitionGuard(directory, fileSystem) {
                            val writers = activeOwnerPaths(directory, fileSystem).filter(Path::isWriteOwner)
                            if (writers.isNotEmpty()) throw ownersInUse(writers)
                            ownerScope.acquireRenewableFileSystemLease(ownerPath, fileSystem, duration)
                                .also { acquired = it }
                        }.also {
                            currentCoroutineContext().ensureActive()
                            ownerJob.ensureActive()
                        }
                    } catch (failure: Throwable) {
                        leaseCleanup(failure) { acquired?.closeAndJoin() }
                        throw failure
                    }
                    val owner = SharedReadOwner(
                        entry = entry,
                        ownerJob = ownerJob,
                        lease = lease,
                    )
                    accounting { state ->
                        val slot = state.entries.getValue(key)
                        state.copy(
                            entries = state.entries + (key to slot.copy(owner = owner)),
                            references = state.references + (owner to 1),
                        ) to Unit
                    }
                    // A timed-out last handle must not discard the retirement
                    // barrier. The actual lease Job removes it on completion,
                    // without launching a new cleanup job or resource scope.
                    lease.coroutineContext.job.invokeOnCompletion { removeRetired(owner) }
                    SharedReadLease(ownerScope, owner)
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                ownerJob.ensureActive()
                handle.coroutineContext.ensureActive()
                return handle
            } catch (failure: Throwable) {
                leaseCleanup(failure) { handle.closeAndJoin() }
                throw failure
            }
        } finally {
            accounting { state ->
                val slot = state.entries.getValue(key)
                val unpinned = slot.copy(acquisitions = slot.acquisitions - 1)
                state.withSlot(key, unpinned) to Unit
            }
        }
    }

    suspend fun release(owner: SharedReadOwner, primary: Throwable?) {
        val last = accounting { state ->
            val references = state.references.getValue(owner)
            check(references > 0) { "Read lease reference count is already zero." }
            val remaining = references - 1
            state.copy(
                references = if (remaining == 0) state.references - owner
                else state.references + (owner to remaining),
            ) to (remaining == 0)
        }
        if (!last) return
        try {
            leaseCleanup(primary) { owner.lease.closeAndJoin() }
        } finally {
            removeRetired(owner)
        }
    }

    private fun removeRetired(owner: SharedReadOwner) {
        accounting { state ->
            val slot = state.entries[owner.entry.key]
            if (slot != null && slot.owner === owner &&
                owner !in state.references && owner.lease.coroutineContext.job.isCompleted
            ) {
                state.withSlot(owner.entry.key, slot.copy(owner = null)) to Unit
            } else {
                state to Unit
            }
        }
    }
}

private data class SharedReadInventory(
    val entries: Map<String, SharedReadSlot> = emptyMap(),
    val references: Map<SharedReadOwner, Int> = emptyMap(),
) {
    fun withSlot(key: String, slot: SharedReadSlot): SharedReadInventory = copy(
        entries = if (slot.acquisitions == 0 && slot.owner == null) entries - key else entries + (key to slot),
    )
}

private data class SharedReadSlot(
    val entry: SharedReadEntry,
    val acquisitions: Int = 0,
    val owner: SharedReadOwner? = null,
)

// A pinned acquisition mutex for one resolved directory, not another resource
// owner. Pins include waiters, so removing an idle entry cannot split one key
// across two acquisition mutexes. Release never takes this mutex.
private class SharedReadEntry(val key: String) {
    val acquisition = Mutex()
}

private class SharedReadOwner(
    val entry: SharedReadEntry,
    val ownerJob: Job,
    val lease: RenewableFileSystemLease,
)

private class SharedReadLease(
    ownerScope: CoroutineScope,
    private val owner: SharedReadOwner,
) : FileSystemLease {
    private val completion = CompletableDeferred<Result<Unit>>()
    private val lifetime = ownerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        var primary: Throwable? = null
        try {
            // Lease loss/renewal exit closes the reference too; there is no
            // idle supervisor left alive after the actual owner has ended.
            owner.lease.coroutineContext.job.join()
            owner.lease.closeAndJoin()
            currentCoroutineContext().cancel()
        } catch (cancellation: CancellationException) {
            // This handle owns exactly one reference, released by this one child.
            // A saved owner failure can itself be a cleanup timeout/cancellation;
            // it must not be mistaken for this reference's close request.
            if (currentCoroutineContext().isActive) primary = cancellation
            currentCoroutineContext().cancel(cancellation)
        } catch (failure: Throwable) {
            primary = failure
            currentCoroutineContext().cancel()
        } finally {
            currentCoroutineContext().cancelChildren()
            val cleanup = runCatching {
                SharedReadLeases.release(owner, primary)
            }
            completion.complete(primary?.let { Result.failure<Unit>(it) } ?: cleanup)
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
}
