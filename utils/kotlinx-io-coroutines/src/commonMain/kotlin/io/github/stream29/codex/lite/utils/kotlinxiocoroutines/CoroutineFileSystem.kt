package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path

/**
 * Coroutine-friendly filesystem boundary using kotlinx-io [Path] and [FileMetadata].
 *
 * JVM uses `Dispatchers.IO` over the blocking kotlinx-io filesystem. Node.js uses
 * `node:fs/promises`. Native currently offloads blocking kotlinx-io calls to
 * a bounded `Dispatchers.Default.limitedParallelism` lane because kotlinx.coroutines
 * does not expose `Dispatchers.IO` there as a public API in this source set.
 */
public interface CoroutineFileSystem {
    public suspend fun exists(path: Path): Boolean

    public suspend fun delete(path: Path, mustExist: Boolean = true)

    public suspend fun createDirectories(path: Path, mustCreate: Boolean = false)

    public suspend fun atomicMove(source: Path, destination: Path)

    public suspend fun metadataOrNull(path: Path): FileMetadata?

    public suspend fun resolve(path: Path): Path

    public suspend fun list(directory: Path): Collection<Path>

    public suspend fun source(path: Path): CoroutineRawSource

    public suspend fun sink(path: Path, append: Boolean = false): CoroutineRawSink

    public suspend fun readBytes(path: Path, maxByteCount: Long = Long.MAX_VALUE): ByteArray =
        source(path).use { it.readBytes(maxByteCount) }

    public suspend fun writeBytes(path: Path, content: ByteArray, append: Boolean = false) {
        sink(path, append).use {
            it.writeBytes(content)
            it.flush()
        }
    }

    public suspend fun readString(path: Path): String =
        readBytes(path).decodeToString()

    public suspend fun writeString(path: Path, content: String, append: Boolean = false) {
        writeBytes(path, content.encodeToByteArray(), append)
    }
}

/**
 * Platform default coroutine filesystem.
 */
public expect val SystemCoroutineFileSystem: CoroutineFileSystem
