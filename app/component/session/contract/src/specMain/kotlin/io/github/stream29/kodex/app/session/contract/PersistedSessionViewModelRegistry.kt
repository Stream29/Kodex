package io.github.stream29.kodex.app.session.contract

/**
 * Application-facing registry for persisted Session ViewModels.
 *
 * This is an ownership port rather than a frontend state projection. It keeps
 * repository and concrete ViewModel-store details outside parent ViewModels
 * while centralizing stable-handle reuse and disposal. Tab handles and RPC
 * heartbeat/recovery views have distinct roles; neither is a backend repository.
 * Management operations retain the original persisted-index RPC meaning.
 *
 * Caller cancellation cancels local waiting, not accepted backend work. Failures
 * propagate; missing data is not synthesized and failed commands are not replayed.
 * [shutdown] ends the local registry, not the shared connection or Home scope.
 */
public interface PersistedSessionViewModelRegistry : PersistedSessionViewModelFactory {
    /**
     * Opens/reuses one child after idempotently unarchiving its root Session.
     * @throws IllegalStateException if the registry is closed.
     * @throws Exception if unarchive/initial activation/reads fail.
     * @throws kotlinx.coroutines.CancellationException if caller waiting or owner is cancelled.
     */
    override suspend fun open(sessionIndex: Int): PersistedSessionViewModel

    /**
     * Releases the current opened child without deleting data or stopping backend work.
     * A late release must not close a different view opened after that child was removed.
     * @throws kotlinx.coroutines.CancellationException if caller cleanup waiting is cancelled.
     */
    public suspend fun release(sessionIndex: Int): Unit

    /**
     * Archives persisted data without releasing a local child.
     * @throws Exception if persisted admission/storage/transport fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled.
     */
    public suspend fun archive(sessionIndex: Int): Unit

    /**
     * Idempotently unarchives a persisted root Session.
     * @throws Exception if persisted admission/storage/transport fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled.
     */
    public suspend fun unarchive(sessionIndex: Int): Unit

    /**
     * Forks root storage without changing source navigation or archived state.
     * @throws Exception if source/running admission/storage/transport fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled.
     */
    public suspend fun fork(sessionIndex: Int): Int

    /**
     * Deletes persisted data and releases a matching local child on true.
     * False means data was absent; errors are not converted to false.
     * @throws Exception if deletion/storage/transport fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled;
     * accepted backend deletion is not undone.
     */
    public suspend fun delete(sessionIndex: Int): Boolean

    /**
     * Idempotently closes every local child and rejects further opens.
     * Cleanup after acceptance must complete even in a cancelled caller's scope.
     * @throws kotlinx.coroutines.CancellationException if cancelled before admission;
     * the host supplies its cleanup scope to ensure shutdown is admitted.
     */
    public suspend fun shutdown(): Unit
}
