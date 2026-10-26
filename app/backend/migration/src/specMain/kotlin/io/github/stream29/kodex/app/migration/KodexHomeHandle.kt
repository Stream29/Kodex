package io.github.stream29.kodex.app.migration

import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

/**
 * A prepared Home and its retained shared read lease.
 *
 * The public `CoroutineScope.prepareKodexHome` factory is declared in this
 * spec and provided by an actual implementation. It returns this contract only
 * after checking the stored version and, when necessary, preparing the Home
 * under an exclusive write lease and reacquiring a read lease. [version] is
 * the persisted migration checkpoint accepted at
 * preparation, not a live view of `version.json` or the running application
 * version. A versioned Home with no applicable migrations retains its read
 * lease without rewriting the version or scanning Session layout, even when
 * the application version differs.
 *
 * Preparation rejects an unreadable or malformed version file, a stored version
 * newer than the application, or a version that is missing or still requires
 * migration at the final read check with [KodexHomeVersionException]. Invalid
 * unversioned Session layout is rejected with [KodexHomeLayoutException]. Registry ordering
 * failures are [IllegalStateException]. Exclusive lease conflicts during
 * initialization or data migration throw [KodexHomeInUseException] with
 * preparation context and the underlying cause. Other filesystem, lease,
 * migration action and migration-start callback failures propagate.
 * Preparation registers a validated unversioned baseline before migrating and
 * records each completed migration target without advancing to releases that
 * require no migration. Legacy application-version checkpoints are accepted
 * without normalization when supported. Preparation is cancellable and does
 * not roll back completed filesystem changes.
 *
 * The receiver scope of the factory owns the lease as a structured child;
 * cancelling that owner initiates release even if this handle is not closed.
 * Keep the owner alive throughout use of structured Home data and close this
 * handle only after the application's Home users have finished. Owner completion
 * waits for lease cleanup to finish; completion alone does not prove cleanup
 * succeeded if the filesystem failed.
 *
 * Closing one handle does not release other handles' shared lease references.
 * [closeAndJoin] observes this reference's saved release result. Preparation
 * failure/cancellation awaits undelivered lease cleanup, retaining the primary
 * error and suppressing a distinct cleanup error. Successful preparation steps
 * whose temporary lease cleanup fails report that cleanup failure.
 *
 * @throws KodexHomeVersionException The actual preparation factory rejects
 * an invalid/unreadable, newer or missing Home version, or remaining migrations
 * at its final read check.
 * @throws KodexHomeLayoutException The actual preparation factory rejects
 * an unversioned Home's Session layout or latest pointers.
 * @throws KodexHomeInUseException Initialization or data migration cannot
 * acquire the exclusive Home lease.
 * @throws IllegalStateException The actual preparation factory rejects
 * duplicate or non-increasing registry targets.
 * @throws IllegalArgumentException The actual preparation factory requires
 * its receiver scope to have an owner Job.
 * @throws CancellationException The actual preparation factory is cancellable;
 * undelivered owner publication is cleaned up before failure returns.
 */
public interface KodexHomeHandle : AutoCloseable {
    /** Structured data root supplied to preparation; not necessarily the default Home. */
    public val home: Path

    /** Persisted migration checkpoint accepted during preparation. */
    public val version: MigrationVersion

    /**
     * Requests lease cancellation without waiting for cleanup.
     *
     * Repeated calls request the same cancellation and do not release a reference
     * twice. This operation does not cancel the factory's owner scope.
     */
    override fun close()

    /**
     * Requests closure and waits for this handle's lease job to finish cleanup.
     *
     * May be called again, including after [close]. Waiting is cancellable:
     * cancellation of the caller does not undo the close request, but can stop
     * the wait before cleanup finishes. Call from an appropriate cleanup context
     * when completion must be awaited despite caller cancellation.
     * Repeated waits observe the same release result. A filesystem cleanup failure
     * is observable and may leave the owner file on disk; it is not retried by
     * another close. A non-final reference does not wait for other handles.
     *
     * @throws CancellationException If the calling coroutine is cancelled while
     * waiting, including when it is already cancelled at the join.
     * @throws Throwable If the retained lease's renewal or cleanup failed.
     */
    public suspend fun closeAndJoin()
}
