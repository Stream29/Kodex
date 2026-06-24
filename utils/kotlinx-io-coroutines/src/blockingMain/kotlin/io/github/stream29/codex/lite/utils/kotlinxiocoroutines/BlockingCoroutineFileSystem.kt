package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

public actual val SystemCoroutineFileSystem: CoroutineFileSystem =
    BlockingCoroutineFileSystem(SystemFileSystem)

internal expect val IoDispatcher: CoroutineDispatcher

private class BlockingCoroutineFileSystem(
    private val delegate: FileSystem,
) : CoroutineFileSystem {
    override suspend fun exists(path: Path): Boolean =
        withContext(IoDispatcher) { delegate.exists(path) }

    override suspend fun delete(path: Path, mustExist: Boolean): Unit =
        withContext(IoDispatcher) { delegate.delete(path, mustExist) }

    override suspend fun createDirectories(path: Path, mustCreate: Boolean): Unit =
        withContext(IoDispatcher) { delegate.createDirectories(path, mustCreate) }

    override suspend fun atomicMove(source: Path, destination: Path): Unit =
        withContext(IoDispatcher) { delegate.atomicMove(source, destination) }

    override suspend fun metadataOrNull(path: Path): FileMetadata? =
        withContext(IoDispatcher) { delegate.metadataOrNull(path) }

    override suspend fun resolve(path: Path): Path =
        withContext(IoDispatcher) { delegate.resolve(path) }

    override suspend fun list(directory: Path): Collection<Path> =
        withContext(IoDispatcher) { delegate.list(directory) }

    override suspend fun readString(path: Path): String =
        withContext(IoDispatcher) {
            val source = delegate.source(path).buffered()
            try {
                source.readString()
            } finally {
                source.close()
            }
        }

    override suspend fun writeString(path: Path, content: String, append: Boolean): Unit =
        withContext(IoDispatcher) {
            val sink = delegate.sink(path, append).buffered()
            try {
                sink.writeString(content)
            } finally {
                sink.close()
            }
        }
}
