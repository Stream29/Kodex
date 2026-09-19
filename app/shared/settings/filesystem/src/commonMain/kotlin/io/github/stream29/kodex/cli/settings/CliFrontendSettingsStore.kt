package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/** CLI-owned preferences and notification commands; never reads or writes backend settings. */
public class CliFrontendSettingsStore internal constructor(
    private val fileSystem: CoroutineFileSystem,
    settingsDirectory: Path,
    private val defaults: CliFrontendSettings,
) {
    private val updateMutex = Mutex()
    public val settingsPath: Path = Path(settingsDirectory, "settings.frontend.cli.yml")

    /** Last successfully loaded or persisted frontend snapshot, without sidebar widths. */
    public val settings: StateFlow<CliFrontendSettings>
        field = MutableStateFlow(defaults)

    /** Reads this file only, using [defaults] for missing fields, without creating a file. */
    public suspend fun reload(): CliFrontendSettings = updateMutex.withLock {
        read().also { settings.value = it }
    }

    /**
     * Serially transforms the latest file value and publishes after persistence succeeds.
     * A lost completion after atomic replacement does not imply that the file is unchanged.
     */
    public suspend fun update(
        transform: (CliFrontendSettings) -> CliFrontendSettings,
    ): CliFrontendSettings = updateMutex.withLock {
        val updated = transform(read())
        writeSplitSettings(fileSystem, settingsPath, encodeCliFrontendSettingsFile(updated))
        updated.also { settings.value = it }
    }

    private suspend fun read(): CliFrontendSettings =
        readSplitSettings(fileSystem, settingsPath, defaults, ::decodeCliFrontendSettingsFile)
}

/** Opens frontend preferences without loading legacy Hooks or backend settings. */
public suspend fun openCliFrontendSettings(
    settingsDirectory: Path,
    defaults: CliFrontendSettings = CliFrontendSettings(),
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): CliFrontendSettingsStore =
    CliFrontendSettingsStore(fileSystem, settingsDirectory, defaults).also { it.reload() }
