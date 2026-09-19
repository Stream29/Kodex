package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/**
 * Backend-owned settings.backend.yml, independent of both frontend and legacy settings.
 *
 * This is a file store, not the RPC settings service: business validation and MCP
 * lifecycle coordination must still be performed by the backend write boundary.
 */
public class BackendSettingsStore internal constructor(
    private val fileSystem: CoroutineFileSystem,
    settingsDirectory: Path,
    private val defaults: BackendSettings,
) {
    private val updateMutex = Mutex()
    public val settingsPath: Path = Path(settingsDirectory, "settings.backend.yml")

    /** Last successfully loaded or persisted complete backend snapshot. */
    public val settings: StateFlow<BackendSettings>
        field = MutableStateFlow(defaults)

    /** Reads this file only; missing fields use backend-supplied [defaults]. Does not write. */
    public suspend fun reload(): BackendSettings = updateMutex.withLock {
        read().also { settings.value = it }
    }

    /**
     * Serially transforms the latest file value and publishes after persistence succeeds.
     *
     * Does not implement RPC CAS or its business side effects. Failure or cancellation
     * does not prove the file was unchanged if atomic replacement already completed.
     */
    public suspend fun update(transform: (BackendSettings) -> BackendSettings): BackendSettings =
        updateMutex.withLock {
            val updated = transform(read())
            writeSplitSettings(fileSystem, settingsPath, encodeBackendSettingsFile(updated))
            updated.also { settings.value = it }
        }

    /**
     * Compares and persists complete values under the same lock as [update] and [reload].
     *
     * A mismatch returns false without writing. An equal update returns true without
     * creating or rewriting a file. Reading a newer file refreshes [settings] even if
     * the proposed update does not match; it never publishes a rejected update.
     *
     * This is process-local file CAS, not cross-process coordination or the complete
     * RPC business operation. Validation and lifecycle side effects still belong to
     * the backend service. I/O, decoding and cancellation failures propagate.
     */
    public suspend fun compareAndSet(expect: BackendSettings, update: BackendSettings): Boolean =
        updateMutex.withLock {
            val current = read()
            settings.value = current
            if (current != expect) return@withLock false
            if (current == update) return@withLock true
            writeSplitSettings(fileSystem, settingsPath, encodeBackendSettingsFile(update))
            settings.value = update
            true
        }

    private suspend fun read(): BackendSettings =
        readSplitSettings(fileSystem, settingsPath, defaults, ::decodeBackendSettingsFile)
}

/** Opens backend settings without reading settings.yml or frontend preferences. */
public suspend fun openBackendSettings(
    settingsDirectory: Path,
    defaults: BackendSettings,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): BackendSettingsStore =
    BackendSettingsStore(fileSystem, settingsDirectory, defaults).also { it.reload() }
