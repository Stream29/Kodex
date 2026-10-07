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
 * [SessionCatalogEntry.threadName], archive state, running state and timestamps
 * from the same snapshot. Owner residency is sampled metadata, not a spinner
 * criterion or an extra live indicator. It routes row actions through
 * this interface. Failures escape suspending commands to their owner; there
 * is no persistent error state in this version. The owner chooses error
 * presentation without treating the restored state as a successful operation.
 * Selection/opening and exact popup dismissal use [SessionCatalogInteractions].
 * The VM owns [deleteTarget] and its exact Session Delete child; menus, focus,
 * scrolling, confirmation-dialog layout and animation belong to the renderer.
 * Renderer mounting refreshes exactly once per VM mount, never from construction.
 * Unmount cancels renderer callers and dismisses its captured Delete handle but
 * does not close the host-owned catalog or backend. Owner close cancels local work.
 * It must not call the dependency directly or open a Session
 * merely to decide how a row is rendered.
 */
public interface SessionCatalogViewModel : AutoCloseable {
    /** The atomic snapshot consumed by the renderer. */
    public val state: StateFlow<SessionCatalogState>

    /** Exact Delete child, or null. Render it directly; do not copy its target or state. */
    public val deleteTarget: StateFlow<SessionCatalogDeleteHandle?>

    /**
     * Opens the exact captured row, then dismisses this popup through its injected port.
     * A row no longer matching a completed snapshot value is a no-op. Structurally
     * equal refreshed rows remain usable (StateFlow may conflate equal snapshots).
     * Navigation always uses the captured entry's index, not the selected/latest row;
     * this does not add permanent identity across backend index reuse.
     * After successful navigation, terminal dismissal may close this catalog child without
     * cancelling the already-completed navigation caller.
     * Failure/cancellation leaves the popup open; fork never invokes this operation.
     *
     * @throws IllegalStateException when navigation ports were omitted for data-only use.
     * @throws CancellationException when the caller or component is cancelled.
     * @throws Exception when the host's open operation fails, unchanged.
     */
    public suspend fun requestOpen(entry: SessionCatalogEntry): Unit

    /**
     * Creates/replaces the exact Session Delete child for a captured completed row.
     * Returns null for a row no longer matching the completed snapshot. Construction
     * does no I/O. Replacement closes
     * the preceding child. Child deletion is serialized with catalog commands,
     * reloads after either Boolean outcome, and dismisses only this handle on true.
     * False keeps the confirmation; mutation/reload failure propagates unchanged
     * and keeps it. An old child cannot dispatch against a newer confirmation.
     * Calls and handle lifecycle are confined to the owner's interaction dispatcher.
     *
     * @throws CancellationException when the component has closed.
     */
    public fun requestDelete(entry: SessionCatalogEntry): SessionCatalogDeleteHandle?

    /**
     * Closes/removes only [handle] if still current. Stale callbacks and repeated
     * dismissals are no-ops. No backend I/O or rollback; callable after close.
     */
    public fun dismissDelete(handle: SessionCatalogDeleteHandle): Unit

    /**
     * Invokes the injected exact-popup dismissal without navigation/backend I/O.
     * @throws IllegalStateException when navigation ports were omitted for data-only use.
     * @throws CancellationException when the component has closed.
     */
    public fun dismiss(): Unit

    /**
     * Loads or reloads the lightweight persisted Session catalog.
     *
     * A failed load restores the prior snapshot. Cancellation is propagated
     * and no partial snapshot is published.
     *
     * @throws CancellationException when the caller or component is cancelled.
     * @throws Exception when the backend load fails, unchanged.
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
     * @throws Exception when the backend load fails; the old filter/snapshot is restored.
     */
    public suspend fun setShowArchived(showArchived: Boolean): Unit

    /**
     * Archives one root Session, then reloads the current snapshot.
     * @throws CancellationException when the caller or component is cancelled.
     * @throws Exception when archive or subsequent reload fails, without rollback/replay.
     */
    public suspend fun archive(sessionIndex: Int): Unit

    /**
     * Unarchives one root Session, then reloads the current snapshot.
     * @throws CancellationException when the caller or component is cancelled.
     * @throws Exception when unarchive or subsequent reload fails, without rollback/replay.
     */
    public suspend fun unarchive(sessionIndex: Int): Unit

    /**
     * Forks one complete root Session, reloads, and returns its new index. Does not open it.
     * @throws CancellationException when the caller or component is cancelled.
     * @throws Exception when fork or subsequent reload fails, without rollback/replay.
     */
    public suspend fun fork(sessionIndex: Int): Int

    /**
     * Deletes one root Session, reloads, and reports whether it existed.
     * @throws CancellationException when the caller or component is cancelled.
     * @throws Exception when deletion or subsequent reload fails, without rollback/replay.
     */
    public suspend fun delete(sessionIndex: Int): Boolean

    /**
     * Cancels owned work. Subsequent commands throw [CancellationException].
     * Closes/removes the owned Delete child. Does not close the backend or cancel
     * the parent, undo accepted mutations, or dismiss a replacement popup;
     * repeated calls are safe. Caller cancellation also cancels its own waiting work.
     */
    override fun close(): Unit
}

/** Creates one lazy, independently disposable catalog popup child. */
public fun interface SessionCatalogViewModelFactory {
    /**
     * Binds one component instance to its backend capabilities.
     *
     * The factory must not perform catalog I/O before the returned ViewModel
     * is explicitly refreshed. [interactions] binds this exact popup opening;
     * null is supported only for data-only consumers/tests. Delete child creation
     * is the implementation's explicit composition of Session Delete spec/impl.
     */
    public fun create(
        dependencies: SessionCatalogDependencies,
        interactions: SessionCatalogInteractions?,
    ): SessionCatalogViewModel
}
