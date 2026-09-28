package io.github.stream29.kodex.app.session.contract

/**
 * Application-facing registry for persisted Session ViewModels.
 *
 * This is an ownership port rather than a frontend state projection. It keeps
 * repository and concrete ViewModel-store details outside parent ViewModels
 * while centralizing stable-handle reuse and disposal.
 */
public interface PersistedSessionViewModelRegistry : PersistedSessionViewModelFactory {
    /** Opens or reuses one child after idempotently unarchiving its root Session. */
    override suspend fun open(sessionIndex: Int): PersistedSessionViewModel

    /** Releases an opened child without deleting its persisted data. */
    public suspend fun release(sessionIndex: Int): Unit

    /** Archives one persisted root Session without releasing an opened child. */
    public suspend fun archive(sessionIndex: Int): Unit

    /** Idempotently unarchives one persisted root Session. */
    public suspend fun unarchive(sessionIndex: Int): Unit

    /** Forks one root Session without changing its opened or archived state. */
    public suspend fun fork(sessionIndex: Int): Int

    /** Deletes persisted data and any matching opened child. */
    public suspend fun delete(sessionIndex: Int): Boolean

    /** Closes every opened child and stops accepting registry work. */
    public suspend fun shutdown(): Unit
}
