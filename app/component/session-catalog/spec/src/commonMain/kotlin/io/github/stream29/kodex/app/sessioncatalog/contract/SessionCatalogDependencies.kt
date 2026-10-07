package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.coroutines.CancellationException

/**
 * Backend capabilities required by the Session Catalog ViewModel.
 *
 * The component owns loading state, archive filtering, operation sequencing,
 * and the renderer-facing snapshot. The dependency owns persistence and
 * application-level Session operations. A dependency must not expose a live
 * Session runtime just to render a catalog row.
 *
 * Failures propagate to the invoking command; they are not represented as
 * successful empty results. Cancellation must remain cancellation. The port
 * does not own the ViewModel's lifetime and must not be closed by the child.
 */
public interface SessionCatalogDependencies {
    /**
     * Reads one lightweight catalog snapshot.
     *
     * The returned list must contain enough data to render every catalog row
     * without opening a Session runtime. The [showArchived] value is a filter,
     * not a permission to mutate archived state.
     * Preserve backend ordering; timestamps and activity flags are sampled
     * values rather than a live subscription. When false, exclude archived
     * entries; when true, include both archived and unarchived entries.
     *
     * @throws CancellationException when the operation is cancelled.
     * @throws Exception when persistence/transport/catalog sampling fails, unchanged.
     */
    public suspend fun load(showArchived: Boolean): List<SessionCatalogEntry>

    /**
     * Archives one persisted root Session.
     * @throws CancellationException when the operation is cancelled.
     * @throws Exception when the owner rejects the target or persistence/transport fails.
     */
    public suspend fun archive(sessionIndex: Int): Unit

    /**
     * Unarchives one persisted root Session.
     * @throws CancellationException when the operation is cancelled.
     * @throws Exception when the owner rejects the target or persistence/transport fails.
     */
    public suspend fun unarchive(sessionIndex: Int): Unit

    /**
     * Forks one complete root Session through the owner and returns the new index.
     * @throws CancellationException when the operation is cancelled.
     * @throws Exception when fork admission, persistence or transport fails; never replay automatically.
     */
    public suspend fun fork(sessionIndex: Int): Int

    /**
     * Deletes one persisted root Session through the owner.
     * Return false if it did not exist. The owner handles affected tabs and
     * children; the catalog must not dispose those resources itself.
     * @throws CancellationException when the operation is cancelled.
     * @throws Exception when deletion fails; failure is not equivalent to a false result.
     */
    public suspend fun delete(sessionIndex: Int): Boolean
}
