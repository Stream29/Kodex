package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.BackendSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.IOException

/**
 * Backend-owned settings projection backed by the unified `settings.yml` store.
 *
 * Each store serializes its operations and callbacks under its lock. Separate
 * instances/processes are not coordinated. This is file persistence, not the RPC
 * settings service: business validation and MCP lifecycle coordination still belong
 * to the backend write boundary.
 *
 * Writes atomically replace `settings.yml` using a private same-directory temporary,
 * then publish the complete snapshot. Writes need not preserve unknown keys or sparse
 * formatting.
 *
 * Failure or cancellation, including lost completion after replacement, does not
 * imply rollback: the file may already be persisted while [settings] remains older.
 * Temporary cleanup is attempted even on cancellation; cleanup failure is suppressed
 * on a primary failure, or propagated if it is the only failure.
 */
public interface BackendSettingsStore {
    /**
     * Last successfully loaded or persisted complete snapshot.
     * Rejected proposals are never published.
     */
    public val settings: StateFlow<BackendSettings>

    /**
     * Runs [transform] exactly once on the current authoritative snapshot under the same lock,
     * persists its result even if equal, then publishes and returns that result.
     * The callback must not reenter this store. Callback failures propagate unchanged.
     * Failed persistence does not publish the proposal, but may have replaced the file.
     *
     * @return The successfully persisted and published snapshot.
     * @throws IllegalArgumentException if YAML, a known setting or encoding is invalid.
     * @throws IOException if writing, replacing or cleaning up fails.
     * @throws CancellationException if cancelled while waiting or operating; cleanup
     * is still attempted and an already persisted result is not rolled back.
     * @throws Throwable if [transform] throws; its original failure is propagated.
     */
    public suspend fun update(transform: (BackendSettings) -> BackendSettings): BackendSettings

    /**
     * Compares complete authoritative values using equality under the same lock as [update].
     * A stale [expect] returns false without writing; a matching proposal equal to the current
     * value returns true without creating or rewriting the file. Both cases skip [validate].
     *
     * Otherwise [validate] runs with the actual current value and proposed [update]
     * before persistence, while the lock is held. Its default is a no-op. The callback
     * must be pure and must not reenter the store; rejected validation propagates
     * unchanged, leaving the file untouched and [settings] at the current snapshot.
     * Successful persistence publishes [update]. This is process-local CAS, not a
     * cross-process lock or the complete RPC business operation.
     *
     * @return Whether the expected value matched, including an equal no-op update.
     * @throws IllegalArgumentException if YAML, a known setting or encoding is invalid.
     * @throws IOException if writing, replacing or cleaning up fails.
     * @throws CancellationException if cancelled while waiting or operating; lost
     * completion does not prove that the proposal was not persisted.
     * @throws Throwable if [validate] throws; its original failure is propagated.
     */
    public suspend fun compareAndSet(
        expect: BackendSettings,
        update: BackendSettings,
        validate: (BackendSettings, BackendSettings) -> Unit = { _, _ -> },
    ): Boolean
}
