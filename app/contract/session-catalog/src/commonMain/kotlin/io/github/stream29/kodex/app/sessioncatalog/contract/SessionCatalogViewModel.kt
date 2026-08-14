package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.coroutines.flow.StateFlow

/** Atomic load state for one persisted Session catalog. */
public sealed interface SessionCatalogState {
    /** No catalog read has started. */
    public data object Unloaded : SessionCatalogState

    /** A catalog read is in progress. */
    public data object Loading : SessionCatalogState

    /** The latest successful catalog snapshot, including a successfully loaded empty catalog. */
    public data class Loaded(
        public val sessions: List<SessionCatalogEntry>,
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

    /** Loads or reloads the complete lightweight persisted Session catalog. */
    public suspend fun refresh(): Unit

    override fun close(): Unit
}

/** Creates one lazy, independently disposable catalog popup child. */
public fun interface SessionCatalogViewModelFactory {
    public fun create(): SessionCatalogViewModel
}
