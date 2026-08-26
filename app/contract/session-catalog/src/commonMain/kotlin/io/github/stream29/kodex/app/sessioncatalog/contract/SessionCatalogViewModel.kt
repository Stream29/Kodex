package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.coroutines.flow.StateFlow

/** Atomic load state for one persisted Session catalog. */
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
 * Construction must perform no catalog I/O and begins [SessionCatalogState.Unloaded].
 * A failed [refresh] escapes to the caller and restores the preceding [state].
 */
public interface SessionCatalogViewModel : AutoCloseable {
    public val state: StateFlow<SessionCatalogState>

    /** Loads or reloads the lightweight persisted Session catalog. */
    public suspend fun refresh(): Unit

    /** Changes the filter and reloads the catalog when it changes. */
    public suspend fun setShowArchived(showArchived: Boolean): Unit

    /** Archives one root Session and updates the current snapshot. */
    public suspend fun archive(sessionIndex: Int): Unit

    /** Unarchives one root Session and updates the current snapshot. */
    public suspend fun unarchive(sessionIndex: Int): Unit

    /** Forks one complete root Session and reloads the current catalog. */
    public suspend fun fork(sessionIndex: Int): Int

    /** Deletes one root Session through the owning Application. */
    public suspend fun delete(sessionIndex: Int): Boolean

    override fun close(): Unit
}

/** Creates one lazy, independently disposable catalog popup child. */
public fun interface SessionCatalogViewModelFactory {
    public fun create(
        forkSession: suspend (sessionIndex: Int) -> Int,
        deleteSession: suspend (sessionIndex: Int) -> Boolean,
    ): SessionCatalogViewModel
}
