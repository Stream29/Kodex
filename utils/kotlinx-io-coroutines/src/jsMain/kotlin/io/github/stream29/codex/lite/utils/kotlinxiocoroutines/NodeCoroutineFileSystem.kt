package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import js.array.toList
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import js.typedarrays.toByteArray
import js.typedarrays.toUint8Array
import node.ErrnoException
import node.buffer.BufferEncoding
import node.buffer.utf8
import node.fs.FileHandle
import node.fs.MkdirAsyncOptions
import node.fs.appendFile as appendFileNode
import node.fs.mkdir
import node.fs.open as openFile
import node.fs.readdir
import node.fs.realpath
import node.fs.rename
import node.fs.rm
import node.fs.rmdir
import node.fs.stat
import node.fs.writeFile as writeFileNode
import node.fs.readFile as readFileNode
import js.buffer.ArrayBuffer

public actual val SystemCoroutineFileSystem: CoroutineFileSystem =
    NodeCoroutineFileSystem

internal actual val IoDispatcher: CoroutineDispatcher =
    Dispatchers.Default

private object NodeCoroutineFileSystem : CoroutineFileSystem {
    override suspend fun exists(path: Path): Boolean =
        try {
            stat(path.toString())
            true
        } catch (error: ErrnoException) {
            if (error.code == "ENOENT") false else throw IOException("Stat failed for $path", error)
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Stat failed for $path")
        }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        if (!exists(path)) {
            if (mustExist) {
                throw FileNotFoundException("File does not exist: $path")
            }
            return
        }
        val metadata = metadataOrNull(path) ?: throw FileNotFoundException("File does not exist: $path")
        try {
            if (metadata.isDirectory) {
                rmdir(path.toString())
            } else {
                rm(path.toString())
            }
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Delete failed for $path")
        }
    }

    override suspend fun createDirectories(path: Path, mustCreate: Boolean) {
        val metadata = metadataOrNull(path)
        if (metadata != null) {
            if (mustCreate) {
                throw IOException("Path already exists: $path")
            }
            if (metadata.isRegularFile) {
                throw IOException("Path already exists and it's a file: $path")
            }
            return
        }
        try {
            mkdir(path.toString(), unsafeJso<MkdirAsyncOptions> { recursive = true })
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Create directories failed for $path")
        }
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (!exists(source)) {
            throw FileNotFoundException("Source does not exist: $source")
        }
        try {
            rename(source.toString(), destination.toString())
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Move failed from $source to $destination")
        }
    }

    override suspend fun metadataOrNull(path: Path): FileMetadata? {
        val stats = try {
            stat(path.toString())
        } catch (error: ErrnoException) {
            if (error.code == "ENOENT") return null
            throw IOException("Stat failed for $path", error)
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Stat failed for $path")
        }
        val isFile = stats.isFile()
        return FileMetadata(
            isRegularFile = isFile,
            isDirectory = stats.isDirectory(),
            size = if (isFile) stats.size.toLong() else -1L,
        )
    }

    override suspend fun fingerprintOrNull(path: Path): FileFingerprint? {
        val stats = try {
            stat(path.toString())
        } catch (error: ErrnoException) {
            if (error.code == "ENOENT") return null
            throw IOException("Stat failed for $path", error)
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Stat failed for $path")
        }
        return FileFingerprint(
            size = if (stats.isDirectory()) -1L else stats.size.toLong(),
            lastModifiedAtNanoseconds = (stats.mtimeMs * NanosecondsPerMillisecond).toLong(),
            fileKey = "${stats.dev}:${stats.ino}",
        )
    }

    override suspend fun resolve(path: Path): Path {
        if (!exists(path)) {
            throw FileNotFoundException(path.toString())
        }
        return try {
            Path(realpath(path.toString()))
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Resolve failed for $path")
        }
    }

    override suspend fun list(directory: Path): Collection<Path> {
        val metadata = metadataOrNull(directory) ?: throw FileNotFoundException(directory.toString())
        if (!metadata.isDirectory) {
            throw IOException("Not a directory: $directory")
        }
        return try {
            readdir(directory.toString())
                .toList()
                .map { Path(directory, it) }
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("List failed for $directory")
        }
    }

    override suspend fun source(path: Path): CoroutineRawSource =
        try {
            NodeCoroutineRawSource(openFile(path.toString(), "r"))
        } catch (error: ErrnoException) {
            if (error.code == "ENOENT") {
                throw FileNotFoundException("File does not exist: $path")
            }
            throw IOException("Open source failed for $path", error)
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Open source failed for $path")
        }

    override suspend fun sink(path: Path, append: Boolean, mustCreate: Boolean): CoroutineRawSink =
        try {
            val flags = when {
                mustCreate && append -> "ax"
                mustCreate -> "wx"
                append -> "a"
                else -> "w"
            }
            NodeCoroutineRawSink(openFile(path.toString(), flags))
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Open sink failed for $path")
        }

    override suspend fun readString(path: Path): String =
        try {
            readFileNode(path.toString(), BufferEncoding.utf8)
        } catch (error: ErrnoException) {
            if (error.code == "ENOENT") {
                throw FileNotFoundException("File does not exist: $path")
            }
            throw IOException("Read failed for $path", error)
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Read failed for $path")
        }

    override suspend fun writeString(
        path: Path,
        content: String,
        append: Boolean,
        mustCreate: Boolean,
    ) {
        if (mustCreate) {
            sink(path, append, mustCreate = true).use { sink ->
                val bytes = content.encodeToByteArray()
                val buffer = Buffer().apply { write(bytes) }
                sink.write(buffer, bytes.size.toLong())
                sink.flush()
            }
            return
        }
        try {
            if (append) {
                appendFileNode(path.toString(), content, BufferEncoding.utf8)
            } else {
                writeFileNode(path.toString(), content, BufferEncoding.utf8)
            }
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Write failed for $path")
        }
    }
}

private class NodeCoroutineRawSource(
    private val handle: FileHandle,
) : CoroutineRawSource {
    private var closed = false

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "Source is closed." }
        if (byteCount == 0L) return 0L
        val readByteCount = minOf(byteCount, CoroutineIoSegmentByteCount.toLong()).toInt()
        val nodeBuffer = Uint8Array<ArrayBuffer>(readByteCount)
        val result = try {
            handle.read(nodeBuffer, 0.0, readByteCount.toDouble(), null)
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Read failed")
        }
        val bytesRead = result.bytesRead.toInt()
        if (bytesRead == 0) return -1L
        sink.write(nodeBuffer.subarray(0, bytesRead).toByteArray())
        return bytesRead.toLong()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            handle.close()
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Close source failed")
        }
    }
}

private class NodeCoroutineRawSink(
    private val handle: FileHandle,
) : CoroutineRawSink {
    private var closed = false

    override suspend fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "Sink is closed." }
        var remaining = byteCount
        while (remaining > 0L) {
            val chunkByteCount = minOf(remaining, CoroutineIoSegmentByteCount.toLong()).toInt()
            val nodeBuffer = source.readByteArray(chunkByteCount).toUint8Array()
            var offset = 0
            while (offset < chunkByteCount) {
                val result = try {
                    handle.write(nodeBuffer, offset.toDouble(), (chunkByteCount - offset).toDouble(), null)
                } catch (error: Throwable) {
                    throw error.asFileSystemIOException("Write failed")
                }
                val bytesWritten = result.bytesWritten.toInt()
                if (bytesWritten <= 0) {
                    throw IOException("Write failed without writing bytes")
                }
                offset += bytesWritten
            }
            remaining -= chunkByteCount
        }
    }

    override suspend fun flush() {
        check(!closed) { "Sink is closed." }
        try {
            handle.sync()
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Flush sink failed")
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            handle.close()
        } catch (error: Throwable) {
            throw error.asFileSystemIOException("Close sink failed")
        }
    }
}

private fun Throwable.asFileSystemIOException(message: String): IOException {
    if (this is CancellationException) throw this
    return IOException(message, this)
}

private const val NanosecondsPerMillisecond: Double = 1_000_000.0
