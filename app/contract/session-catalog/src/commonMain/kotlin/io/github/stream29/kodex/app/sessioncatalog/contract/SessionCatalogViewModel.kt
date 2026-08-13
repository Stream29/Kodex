package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Lazy catalog child consumed by the Select Session popup.
 *
 * Construction must perform no catalog I/O. [sessions] is empty before the
 * first successful [refresh] unless an implementation already has a shared
 * cache. A failed refresh escapes to the caller and retains the last successful
 * list.
 */
public interface SessionCatalogViewModel : AutoCloseable {
    public val sessions: StateFlow<List<SessionCatalogEntry>>

    /** Loads or reloads the complete lightweight persisted Session catalog. */
    public suspend fun refresh(): Unit

    override fun close(): Unit
}

/** Creates one lazy, independently disposable catalog popup child. */
public fun interface SessionCatalogViewModelFactory {
    public fun create(): SessionCatalogViewModel
}
