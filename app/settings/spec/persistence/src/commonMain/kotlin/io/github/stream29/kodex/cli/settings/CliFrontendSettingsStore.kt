package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * CLI-owned preferences and notification commands, without persisted sidebar widths.
 * Never reads or writes backend settings or legacy settings.yml.
 *
 * Each store serializes reads, transforms and writes with its own lock; it does not
 * coordinate separate instances/processes. Missing files/fields use opening defaults,
 * unknown keys are ignored, and invalid known values fail. Reads do not write files.
 * Writes atomically replace only this side's file using a private same-directory
 * temporary, then publish; sparse formatting and unknown keys need not be preserved.
 *
 * Failure or cancellation, including lost completion after replacement, does not
 * imply rollback: the file may be persisted while [settings] remains older.
 * Temporary cleanup is attempted even on cancellation; cleanup failure is suppressed
 * on a primary failure, or propagated when it is the only failure. There is no
 * transaction or rollback across frontend and backend files.
 */
public interface CliFrontendSettingsStore {
    /** Last successfully loaded or persisted frontend snapshot. */
    public val settings: StateFlow<CliFrontendSettings>

    /** Path to this store's settings.frontend.cli.yml only. */
    public val settingsPath: Path

    /**
     * Reads this file under the operation lock, using opening defaults for missing
     * values, and publishes the result without creating or rewriting a file.
     * A failed read leaves the last published snapshot unchanged.
     *
     * @return The newly published frontend snapshot.
     * @throws IllegalArgumentException if YAML or a known setting is invalid.
     * @throws IOException if checking or reading the file fails.
     * @throws CancellationException if cancelled while waiting or reading.
     */
    public suspend fun reload(): CliFrontendSettings

    /**
     * Reads the latest file and invokes [transform] exactly once under the same lock,
     * persists its result even if equal, then publishes and returns it.
     * The callback must not reenter the store; its failures propagate unchanged.
     * Failed persistence does not publish the proposal, but may have replaced the file.
     *
     * @return The successfully persisted and published frontend snapshot.
     * @throws IllegalArgumentException if YAML, a known setting or encoding is invalid.
     * @throws IOException if reading, writing, replacing or cleaning up fails.
     * @throws CancellationException if cancelled while waiting or operating; cleanup
     * is still attempted and an already persisted result is not rolled back.
     * @throws Throwable if [transform] throws; its original failure is propagated.
     */
    public suspend fun update(
        transform: (CliFrontendSettings) -> CliFrontendSettings,
    ): CliFrontendSettings
}
