package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.FILE
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.strerror
import platform.posix.stat

public actual val SystemCoroutineFileSystem: CoroutineFileSystem =
    BlockingCoroutineFileSystem(
        delegate = SystemFileSystem,
        exclusiveSink = ::exclusiveSink,
        fingerprint = ::fingerprintOrNull,
        protectPrivateFile = ::protectPrivateFile,
    )

@OptIn(ExperimentalForeignApi::class)
private fun fingerprintOrNull(path: Path): FileFingerprint? = memScoped {
    val attributes = alloc<stat>()
    if (platform.posix.stat(path.toString(), attributes.ptr) != 0) {
        if (errno == platform.posix.ENOENT) return@memScoped null
        throw IOException("Stat failed for $path: ${strerror(errno)?.toKString()}")
    }
    FileFingerprint(
        size = attributes.st_size,
        lastModifiedAtNanoseconds = attributes.modifiedAtNanoseconds(),
        fileKey = "${attributes.st_dev}:${attributes.st_ino}",
    )
}

internal expect fun stat.modifiedAtNanoseconds(): Long

internal expect fun protectPrivateFile(path: Path)

@OptIn(ExperimentalForeignApi::class)
private fun exclusiveSink(path: Path, append: Boolean): CoroutineRawSink {
    val file = fopen(path.toString(), if (append) "abx" else "wbx")
        ?: throw IOException("Exclusive open failed for $path: ${strerror(errno)?.toKString()}")
    return PosixExclusiveCoroutineRawSink(file)
}

@OptIn(ExperimentalForeignApi::class)
private class PosixExclusiveCoroutineRawSink(
    private val file: CPointer<FILE>,
) : CoroutineRawSink {
    private var closed: Boolean = false

    override suspend fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "Sink is closed." }
        withContext(IoDispatcher) {
            var remaining = byteCount
            while (remaining > 0L) {
                val bytes = source.readByteArray(
                    minOf(remaining, CoroutineIoSegmentByteCount.toLong()).toInt(),
                )
                val written = bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file).toLong()
                }
                if (written != bytes.size.toLong()) {
                    throw IOException("Write failed: ${strerror(errno)?.toKString()}")
                }
                remaining -= written
            }
        }
    }

    override suspend fun flush() {
        check(!closed) { "Sink is closed." }
        withContext(IoDispatcher) {
            if (fflush(file) != 0) throw IOException("Flush failed: ${strerror(errno)?.toKString()}")
        }
    }

    override suspend fun close() {
        if (closed) return
        withContext(IoDispatcher) {
            if (closed) return@withContext
            closed = true
            if (fclose(file) != 0) throw IOException("Close failed: ${strerror(errno)?.toKString()}")
        }
    }
}
