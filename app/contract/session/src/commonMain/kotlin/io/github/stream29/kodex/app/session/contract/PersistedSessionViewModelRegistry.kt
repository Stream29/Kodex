package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.openai.KodexAgentSettings

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

    /**
     * Creates, initializes, and opens one persisted Session.
     *
     * [initialSettings] is evaluated only after the repository has allocated
     * the final Session index.
     */
    public suspend fun create(
        initialSettings: (sessionIndex: Int) -> KodexAgentSettings,
    ): PersistedSessionViewModel

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

    /** Rolls back a Session returned by [create] after later draft work fails. */
    public suspend fun rollbackCreated(sessionIndex: Int): Unit

    /** Closes every opened child and stops accepting registry work. */
    public suspend fun shutdown(): Unit
}
