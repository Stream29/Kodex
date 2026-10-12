package io.github.stream29.kodex.utils.filesystemlease

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException

/**
 * An acquired lease owned by a structured child of its factory's receiver scope.
 *
 * Owner completion waits for resource cleanup. Shared read handles release only
 * their own reference; the last reference waits for physical owner cleanup.
 * Lease loss or renewal failure ends this resource's child, not its parent owner.
 * Failed filesystem cleanup can leave the owner file on disk.
 */
public interface FileSystemLease : AutoCloseable, CoroutineScope {
    /** Requests this child's closure, without cancelling its parent or waiting. Idempotent. */
    override fun close()

    /**
     * Requests closure, waits for this child's cleanup, and observes its saved result.
     *
     * Repeated calls observe the same result and never release another reference.
     * Caller cancellation stops only the wait, not resource cleanup. A non-final
     * shared read reference does not wait for other handles to close.
     *
     * @throws CancellationException If the caller is cancelled while waiting.
     * @throws Throwable If renewal or release failed. The original failure is
     * retained, with a distinct cleanup failure suppressed if both occurred.
     */
    public suspend fun closeAndJoin()
}

public class FileSystemLeaseInUseException(
    message: String,
) : IllegalStateException(message)
