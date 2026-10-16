package io.github.stream29.kodex.app.migration.v0_4_7

import com.charleskorn.kaml.YamlMap
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path

/** Writes only missing, prevalidated targets, then deletes the legacy source last. */
internal suspend fun migrateToV0_4_7(home: Path, fileSystem: CoroutineFileSystem) {
    val source = Path(home, "settings.yml")
    val backend = Path(home, "settings.backend.yml")
    val frontend = Path(home, "settings.frontend.cli.yml")
    suspend fun read(path: Path): YamlMap? {
        val metadata = fileSystem.metadataOrNull(path) ?: return null
        if (!metadata.isRegularFile) throw IOException("Settings path is not a regular file: $path")
        return checked(path) { SettingsCodec.parse(fileSystem.readString(path)) }
    }
    val old = read(source)
    val currentBackend = read(backend)?.let { checked(backend) { SettingsCodec.backend(it) } }
    val currentFrontend = read(frontend)?.let { checked(frontend) { SettingsCodec.frontend(it) } }
    if (old == null) {
        if ((currentBackend == null) != (currentFrontend == null)) {
            throw IOException("Ambiguous split settings: the source is absent and only one target exists.")
        }
        return
    }
    val (expectedBackend, expectedFrontend) = checked(source) { SettingsCodec.legacy(old) }
    if (currentBackend != null && !currentBackend.equivalentContentTo(expectedBackend)) {
        throw IOException("Backend settings conflict with the legacy source: $backend")
    }
    if (currentFrontend != null && !currentFrontend.equivalentContentTo(expectedFrontend)) {
        throw IOException("Frontend settings conflict with the legacy source: $frontend")
    }
    if (currentBackend == null) write(fileSystem, backend, SettingsCodec.encode(expectedBackend))
    if (currentFrontend == null) write(fileSystem, frontend, SettingsCodec.encode(expectedFrontend))
    currentCoroutineContext().ensureActive()
    fileSystem.delete(source)
}

private suspend inline fun <T> checked(path: Path, block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    // Serializer diagnostics can contain MCP credentials or old command contents.
    throw IOException("Invalid settings for migration 0.4.7: $path")
}

private suspend fun write(fileSystem: CoroutineFileSystem, target: Path, contents: String) {
    val temporary = Path(requireNotNull(target.parent), ".${target.name}.migration-0.4.7.tmp")
    var primary: Throwable? = null
    try {
        fileSystem.delete(temporary, mustExist = false)
        fileSystem.writePrivateString(temporary, contents, mustCreate = true)
        currentCoroutineContext().ensureActive()
        fileSystem.atomicMove(temporary, target)
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        try { withContext(NonCancellable) { fileSystem.delete(temporary, mustExist = false) } }
        catch (error: Throwable) {
            if (primary == null) throw error else primary.addSuppressed(error)
        }
    }
}
