package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.files.Path

/** CLI-owned preferences and notification commands; never reads or writes backend settings. */
internal class FileSystemCliFrontendSettingsStore(
    private val fileSystem: CoroutineFileSystem,
    settingsDirectory: Path,
    private val defaults: CliFrontendSettings,
) : CliFrontendSettingsStore {
    private val updateMutex = Mutex()
    override val settingsPath: Path = Path(settingsDirectory, "settings.frontend.cli.yml")

    /** Last successfully loaded or persisted frontend snapshot, without sidebar widths. */
    override val settings: StateFlow<CliFrontendSettings>
        field = MutableStateFlow(defaults)

    /** Reads this file only, using [defaults] for missing fields, without creating a file. */
    override suspend fun reload(): CliFrontendSettings = updateMutex.withLock {
        read().also { settings.value = it }
    }

    /**
     * Serially transforms the latest file value and publishes after persistence succeeds.
     * A lost completion after atomic replacement does not imply that the file is unchanged.
     */
    override suspend fun update(
        transform: (CliFrontendSettings) -> CliFrontendSettings,
    ): CliFrontendSettings = updateMutex.withLock {
        val updated = transform(read())
        writeSplitSettings(fileSystem, settingsPath, encodeCliFrontendSettingsFile(updated))
        updated.also { settings.value = it }
    }

    private suspend fun read(): CliFrontendSettings =
        readSplitSettings(fileSystem, settingsPath, defaults, ::decodeCliFrontendSettingsFile)
}

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
public suspend fun openCliFrontendSettings(
    settingsDirectory: Path,
    defaults: CliFrontendSettings = CliFrontendSettings(),
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): CliFrontendSettingsStore =
    FileSystemCliFrontendSettingsStore(fileSystem, settingsDirectory, defaults).also { it.reload() }
