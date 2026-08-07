package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path

/**
 * Filesystem identity used to validate content caches.
 *
 * @property size Current byte size, or the platform's directory sentinel.
 * @property lastModifiedAtNanoseconds Platform modification timestamp expressed
 * in nanoseconds in that platform's native epoch.
 * @property fileKey Nullable because some filesystem implementations cannot
 * expose a stable object identity; `null` means timestamp and size are the
 * only available identity components.
 */
public data class FileFingerprint(
    public val size: Long,
    public val lastModifiedAtNanoseconds: Long,
    public val fileKey: String?,
)

/**
 * Coroutine-friendly filesystem boundary using kotlinx-io [Path] and [FileMetadata].
 *
 * JVM uses `Dispatchers.IO` over the blocking kotlinx-io filesystem. Node.js uses
 * `node:fs/promises`. Native offloads blocking kotlinx-io calls to its elastic
 * `Dispatchers.IO` worker pool without consuming `Dispatchers.Default` workers.
 */
public interface CoroutineFileSystem {
    public suspend fun exists(path: Path): Boolean

    public suspend fun delete(path: Path, mustExist: Boolean = true)

    public suspend fun createDirectories(path: Path, mustCreate: Boolean = false)

    public suspend fun atomicMove(source: Path, destination: Path)

    public suspend fun metadataOrNull(path: Path): FileMetadata?

    /**
     * Returns the current cache fingerprint for [path].
     *
     * @return `null` only when [path] does not exist.
     */
    public suspend fun fingerprintOrNull(path: Path): FileFingerprint?

    public suspend fun resolve(path: Path): Path

    public suspend fun list(directory: Path): Collection<Path>

    public suspend fun source(path: Path): CoroutineRawSource

    public suspend fun sink(
        path: Path,
        append: Boolean = false,
        mustCreate: Boolean = false,
    ): CoroutineRawSink

    public suspend fun readBytes(path: Path, maxByteCount: Long = Long.MAX_VALUE): ByteArray =
        source(path).use { it.readBytes(maxByteCount) }

    public suspend fun writeBytes(
        path: Path,
        content: ByteArray,
        append: Boolean = false,
        mustCreate: Boolean = false,
    ) {
        sink(path, append, mustCreate).use {
            it.writeBytes(content)
            it.flush()
        }
    }

    public suspend fun readString(path: Path): String =
        readBytes(path).decodeToString()

    public suspend fun writeString(
        path: Path,
        content: String,
        append: Boolean = false,
        mustCreate: Boolean = false,
    ) {
        writeBytes(path, content.encodeToByteArray(), append, mustCreate)
    }
}

/**
 * Platform default coroutine filesystem.
 */
public expect val SystemCoroutineFileSystem: CoroutineFileSystem
