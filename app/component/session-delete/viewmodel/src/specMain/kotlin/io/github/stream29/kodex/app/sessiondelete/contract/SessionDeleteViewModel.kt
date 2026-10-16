package io.github.stream29.kodex.app.sessiondelete.contract

import kotlin.coroutines.cancellation.CancellationException

/**
 * Persisted-session deletion capability bound to the host's owning registry or
 * catalog. Deleting the supplied index must never mean deleting the currently active
 * tab. The host remains responsible for tab cleanup and catalog refresh.
 */
public fun interface SessionDeleteDependencies {
    /**
     * Returns the host's deletion outcome, false when the target no longer exists.
     * A normal Boolean return is distinct from operation failure/cancellation.
     *
     * @throws CancellationException if the caller or operation is cancelled.
     * @throws Exception if deletion fails.
     */
    public suspend fun delete(sessionIndex: Int): Boolean
}

/**
 * One confirmation interaction for a captured persisted session. Construction
 * binds [sessionIndex], [threadName] and [SessionDeleteDependencies]; no live
 * Application/Catalog lookup or mutable target selection occurs in the component.
 *
 * Render "Delete <title>?" using [threadName], or "Session <index>" when null,
 * and warn that persisted data will be removed. Cancel is initially focused;
 * Delete is a destructive action. Cancel/dismiss performs no deletion.
 *
 * The host decides navigation after [delete]: Application dismisses after either
 * Boolean outcome, whereas Catalog dismisses only on true. The renderer reports
 * the unchanged result rather than interpreting false as an exception or closing
 * a parent itself. Do not deliver a completion callback from an already closed
 * child to a replacement confirmation. Failures/cancellation do not imply
 * successful dismissal.
 *
 * Calls are confined to the owner's interaction dispatcher. No duplicate-call
 * suppression or queue is provided. The host/renderer owns caller jobs and must
 * not apply a late callback to a different popup instance.
 */
public interface SessionDeleteViewModel : AutoCloseable {
    /** Stable identity captured when the confirmation opened. */
    public val sessionIndex: Int

    /** Optional title snapshot; does not track subsequent external renames. */
    public val threadName: String?

    /** Whether new deletion calls are accepted. */
    public val isActive: Boolean

    /**
     * Calls the bound capability once for [sessionIndex] and returns its result.
     * Does not dismiss or close this child, change parent state or retry failures.
     *
     * @throws IllegalStateException if this child was already closed.
     * @throws CancellationException if the caller or dependency is cancelled.
     * @throws Exception if the dependency fails.
     */
    public suspend fun delete(): Boolean

    /**
     * Idempotently rejects new calls; does not close the registry/catalog, delete
     * anything or cancel/roll back a command already dispatched.
     */
    override fun close(): Unit
}
