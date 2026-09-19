package io.github.stream29.kodex.cli.settings

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal suspend fun <T> readSplitSettings(
    fileSystem: CoroutineFileSystem,
    path: Path,
    defaults: T,
    decode: (String, T) -> T,
): T {
    if (!fileSystem.exists(path)) return defaults
    val text = fileSystem.readString(path)
    return try {
        decode(text, defaults)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw IllegalArgumentException("Invalid settings YAML at $path.", failure)
    }
}

/** Shared file publication only; the two stores own their separate write mutexes and state. */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun writeSplitSettings(
    fileSystem: CoroutineFileSystem,
    path: Path,
    contents: String,
) {
    val directory = requireNotNull(path.parent)
    val temporary = Path(directory, ".${path.name}.${Uuid.generateV7()}.tmp")
    var primaryFailure: Throwable? = null
    try {
        fileSystem.createDirectories(directory)
        fileSystem.writePrivateString(temporary, "$contents\n", mustCreate = true)
        currentCoroutineContext().ensureActive()
        fileSystem.atomicMove(temporary, path)
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        try {
            withContext(NonCancellable) {
                withTimeout(10.seconds) {
                    fileSystem.delete(temporary, mustExist = false)
                }
            }
        } catch (cleanupFailure: Throwable) {
            val primary = primaryFailure
            if (primary == null) throw cleanupFailure
            primary.addSuppressed(cleanupFailure)
        }
    }
}
