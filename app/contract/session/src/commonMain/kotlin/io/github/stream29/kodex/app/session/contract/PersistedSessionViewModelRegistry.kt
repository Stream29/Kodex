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

    /** Deletes persisted data and any matching opened child. */
    public suspend fun delete(sessionIndex: Int): Boolean

    /** Rolls back a Session returned by [create] after later draft work fails. */
    public suspend fun rollbackCreated(sessionIndex: Int): Unit

    /** Closes every opened child and stops accepting registry work. */
    public suspend fun shutdown(): Unit
}
