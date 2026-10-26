@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * One runtime authority and one atomic settings.yml publication boundary.
 * Normal saves use runtime state, deliberately overwriting un-reloaded external edits.
 * Reload is staged then CAS-accepted with the backend's business validation/manager boundary.
 */
public expect class UnifiedSettingsStore {
    public val path: Path
    /** The only whole-file authority. CAS/updates read it under mutex, never from projections. */
    public val current: UnifiedSettings
    public val backendSettings: StateFlow<BackendSettings>
    public val frontendSettings: StateFlow<CliFrontendSettings>
    public val backendStore: BackendSettingsStore
    public suspend fun compareAndSetFrontend(expect: CliFrontendSettings, update: CliFrontendSettings): Boolean

    /** Parsing never publishes, writes, normalizes or resets the running snapshot. */
    public suspend fun readCandidate(): UnifiedSettings

    /** Invoke through the backend MCP configuration coordination boundary. Does not rewrite disk. */
    public suspend fun acceptReload(
        expected: UnifiedSettings,
        candidate: UnifiedSettings,
        validate: (BackendSettings, BackendSettings) -> Unit,
    ): Boolean

    public companion object {
        public suspend fun open(
            home: Path,
            defaults: BackendSettings,
            fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
        ): UnifiedSettingsStore
    }
}
