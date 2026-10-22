package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * Opens and reloads backend settings before returning the actual store interface.
 * Uses [defaults] for missing files/fields without creating a file. Reads only
 * settings.backend.yml; [defaults] (including Shell selection) must come from the
 * backend environment. Operations use [fileSystem], defaulting to the system filesystem.
 *
 * @throws IllegalArgumentException if YAML or a known setting is invalid.
 * @throws IOException if checking or reading the file fails.
 * @throws CancellationException if opening/reloading is cancelled.
 */
public expect suspend fun openBackendSettings(
    settingsDirectory: Path,
    defaults: BackendSettings,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): BackendSettingsStore

/**
 * Opens and reloads frontend preferences before returning the actual store interface.
 * Missing files/fields use [defaults], which defaults to the compiled [CliFrontendSettings]
 * preferences. Does not create a file or load legacy Hooks/backend settings.
 * Operations use [fileSystem], defaulting to the system filesystem.
 *
 * @throws IllegalArgumentException if YAML or a known setting is invalid.
 * @throws IOException if checking or reading the file fails.
 * @throws CancellationException if opening/reloading is cancelled.
 */
public expect suspend fun openCliFrontendSettings(
    settingsDirectory: Path,
    defaults: CliFrontendSettings = CliFrontendSettings(),
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): CliFrontendSettingsStore
