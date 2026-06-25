package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

    override suspend fun source(path: Path): CoroutineRawSource =
        withContext(IoDispatcher) { BlockingCoroutineRawSource(delegate.source(path)) }

    override suspend fun sink(path: Path, append: Boolean): CoroutineRawSink =
        withContext(IoDispatcher) { BlockingCoroutineRawSink(delegate.sink(path, append)) }
}

private class BlockingCoroutineRawSource(
    private val delegate: RawSource,
) : CoroutineRawSource {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        if (byteCount == 0L) return 0L
        return withContext(IoDispatcher) {
            delegate.readAtMostTo(sink, minOf(byteCount, CoroutineIoSegmentByteCount.toLong()))
        }
    }

    override suspend fun close(): Unit =
        withContext(IoDispatcher) { delegate.close() }
}

private class BlockingCoroutineRawSink(
    private val delegate: RawSink,
) : CoroutineRawSink {
    override suspend fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        withContext(IoDispatcher) {
            var remaining = byteCount
            while (remaining > 0L) {
                val writeByteCount = minOf(remaining, CoroutineIoSegmentByteCount.toLong())
                delegate.write(source, writeByteCount)
                remaining -= writeByteCount
            }
        }
    }

    override suspend fun flush(): Unit =
        withContext(IoDispatcher) { delegate.flush() }

    override suspend fun close(): Unit =
        withContext(IoDispatcher) { delegate.close() }
}
