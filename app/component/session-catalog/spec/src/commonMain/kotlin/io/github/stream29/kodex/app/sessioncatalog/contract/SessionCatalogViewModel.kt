package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Atomic render state for one persisted Session catalog.
 *
 * A renderer must branch on the concrete state: [Unloaded] and [Loading]
 * show progress rather than an empty-catalog message. [Loading] retains
 * previous entries in its snapshot, but they are not fresh completed rows.
 * [Loaded] shows exactly [Loaded.sessions], including a distinct empty state.
 * The renderer must not infer loading, archive mode, or operation completion
 * from a collection being empty.
 */
public sealed interface SessionCatalogState {
    /** Whether this snapshot includes archived Sessions. */
    public val showArchived: Boolean

    /** The catalog entries belonging to this snapshot. */
    public val sessions: List<SessionCatalogEntry>

    /** No catalog read has started. */
    public data object Unloaded : SessionCatalogState {
        override val showArchived: Boolean = false
        override val sessions: List<SessionCatalogEntry> = emptyList()
    }

    /** A catalog read is in progress. */
    public data class Loading(
        override val showArchived: Boolean,
        override val sessions: List<SessionCatalogEntry> = emptyList(),
    ) : SessionCatalogState

    /** The latest successful catalog snapshot, including a successfully loaded empty catalog. */
    public data class Loaded(
        override val showArchived: Boolean,
        override val sessions: List<SessionCatalogEntry>,
    ) : SessionCatalogState
}

/**
 * Lazy catalog child consumed by the Select Session popup.
 *
 * The implementation is constructed with [SessionCatalogDependencies].
 * Construction performs no catalog I/O and begins
 * [SessionCatalogState.Unloaded]. A failed [refresh] escapes to the caller
 * and restores the preceding [state]. All suspending commands are serialized,
 * including snapshot reads. Mutating commands reload the current archive
 * filter before completing.
 * If a mutation succeeds but the following reload fails, the backend
 * mutation is not rolled back; the last published snapshot is restored and
 * the failure propagates. A later refresh is required to observe new data.
 *
 * The renderer initiates [refresh] when opening the component and renders
 * [SessionCatalogEntry.threadName], archive state, running state, activity
 * state, and timestamps from the same snapshot. It routes row actions through
 * this interface. Failures escape suspending commands to their owner; there
 * is no persistent error state in this version. The owner chooses error
 * presentation without treating the restored state as a successful operation.
 * Selection/opening and popup dismissal belong to the parent; menus, focus,
 * scrolling, confirmation-dialog layout and animation belong to the renderer.
 * It must not call the dependency directly or open a Session
 * merely to decide how a row is rendered.
 */
public interface SessionCatalogViewModel : AutoCloseable {
    /** The atomic snapshot consumed by the renderer. */
    public val state: StateFlow<SessionCatalogState>

    /**
     * Loads or reloads the lightweight persisted Session catalog.
     *
     * A failed load restores the prior snapshot. Cancellation is propagated
     * and no partial snapshot is published.
     *
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun refresh(): Unit

    /**
     * Reads the timestamp-zero value from the current catalog snapshot.
     * No additional backend I/O or runtime activation is performed.
     * @return null when exact timestamp zero is absent; later records are not searched.
     * @throws NoSuchElementException if the index is not in the current snapshot.
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun readCreatedAt(sessionIndex: Int): Instant?

    /**
     * Reads the target's latest persisted timestamp sampled in the current
     * catalog snapshot, without additional backend I/O.
     * @return null when no timestamp has been persisted.
     * @throws NoSuchElementException if the index is not in the current snapshot.
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun readUpdatedAt(sessionIndex: Int): Instant?

    /**
     * Changes the archive filter and reloads the catalog when it changes.
     *
     * Repeating the current value is a no-op. The renderer should show the
     * filter mode from [SessionCatalogState.showArchived], not from a local
     * copy.
     *
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun setShowArchived(showArchived: Boolean): Unit

    /**
     * Archives one root Session, then reloads the current snapshot.
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun archive(sessionIndex: Int): Unit

    /**
     * Unarchives one root Session, then reloads the current snapshot.
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun unarchive(sessionIndex: Int): Unit

    /**
     * Forks one complete root Session, reloads, and returns its new index.
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun fork(sessionIndex: Int): Int

    /**
     * Deletes one root Session, reloads, and reports whether it existed.
     * @throws CancellationException when the caller or component is cancelled.
     */
    public suspend fun delete(sessionIndex: Int): Boolean

    /**
     * Cancels owned work. Subsequent commands throw [CancellationException].
     * Does not close the backend or cancel the parent; repeated calls are safe.
     */
    override fun close(): Unit
}

/** Creates one lazy, independently disposable catalog popup child. */
public fun interface SessionCatalogViewModelFactory {
    /**
     * Binds one component instance to its backend capabilities.
     *
     * The factory must not perform catalog I/O before the returned ViewModel
     * is explicitly refreshed.
     */
    public fun create(dependencies: SessionCatalogDependencies): SessionCatalogViewModel
}
