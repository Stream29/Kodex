@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import platform.windows.CREATE_ALWAYS
import platform.windows.CreateFileW
import platform.windows.CreateDirectoryW
import platform.windows.DeleteFileW
import platform.windows.ERROR_ALREADY_EXISTS
import platform.windows.ERROR_FILE_EXISTS
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_NO_MORE_FILES
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.FILE_APPEND_DATA
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_READ_ATTRIBUTES
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FindClose
import platform.windows.FindFirstFileW
import platform.windows.FindNextFileW
import platform.windows.FlushFileBuffers
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetFinalPathNameByHandleW
import platform.windows.GetLastError
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.MOVEFILE_REPLACE_EXISTING
import platform.windows.MoveFileExW
import platform.windows.OPEN_ALWAYS
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.RemoveDirectoryW
import platform.windows.VOLUME_NAME_DOS
import platform.windows.WIN32_FIND_DATAW
import platform.windows.WriteFile
import platform.windows.CloseHandle

public actual val SystemCoroutineFileSystem: CoroutineFileSystem =
    MingwCoroutineFileSystem

private object MingwCoroutineFileSystem : CoroutineFileSystem {
    override suspend fun exists(path: Path): Boolean =
        withContext(IoDispatcher) { windowsMetadataOrNull(path) != null }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        withContext(IoDispatcher) {
            val metadata = windowsMetadataOrNull(path)
            if (metadata == null) {
                if (mustExist) throw missingFile(path)
                return@withContext
            }

            val success =
                if (metadata.isDirectory) RemoveDirectoryW(path.windowsPath())
                else DeleteFileW(path.windowsPath())
            if (success == 0) throw pathFailure("Delete", path, GetLastError().toInt())
        }
    }

    override suspend fun createDirectories(path: Path, mustCreate: Boolean) {
        withContext(IoDispatcher) {
            val existing = windowsMetadataOrNull(path)
            if (existing != null) {
                if (mustCreate) throw IOException("Path already exists: $path")
                if (existing.isRegularFile) {
                    throw IOException("Path already exists and it's a file: $path")
                }
                return@withContext
            }

            val missing = buildList {
                var current: Path? = path
                while (current != null && windowsMetadataOrNull(current) == null) {
                    add(current)
                    current = current.parent
                }
            }
            for (directory in missing.asReversed()) {
                if (CreateDirectoryW(directory.windowsPath(), null) != 0) continue

                val error = GetLastError().toInt()
                if (error == ERROR_ALREADY_EXISTS || error == ERROR_FILE_EXISTS) {
                    val concurrent = windowsMetadataOrNull(directory)
                    if (concurrent?.isDirectory == true && (!mustCreate || directory != path)) continue
                    if (concurrent != null) throw IOException("Path already exists: $directory")
                }
                throw pathFailure("Create directory", directory, error)
            }
        }
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        withContext(IoDispatcher) {
            if (windowsMetadataOrNull(source) == null) throw missingFile(source)
            if (MoveFileExW(source.windowsPath(), destination.windowsPath(), MOVEFILE_REPLACE_EXISTING.toUInt()) == 0) {
                throw IOException("Move failed from $source to $destination: Windows error ${GetLastError()}.")
            }
        }
    }

    override suspend fun metadataOrNull(path: Path): FileMetadata? =
        withContext(IoDispatcher) { windowsMetadataOrNull(path) }

    override suspend fun resolve(path: Path): Path =
        withContext(IoDispatcher) {
            val handle = openHandle(
                path = path,
                desiredAccess = FILE_READ_ATTRIBUTES.toUInt(),
                creationDisposition = OPEN_EXISTING.toUInt(),
                flagsAndAttributes = FILE_FLAG_BACKUP_SEMANTICS.toUInt(),
                operation = "Resolve",
            )
            try {
                Path(finalPath(handle).fromWindowsNamespace())
            } finally {
                closeHandle(handle, "Close resolved path")
            }
        }

    override suspend fun list(directory: Path): Collection<Path> =
        withContext(IoDispatcher) { listDirectory(directory) }

    override suspend fun source(path: Path): CoroutineRawSource =
        withContext(IoDispatcher) {
            MingwCoroutineRawSource(
                openHandle(
                    path = path,
                    desiredAccess = GENERIC_READ,
                    creationDisposition = OPEN_EXISTING.toUInt(),
                    flagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
                    operation = "Open source",
                ),
            )
        }

    override suspend fun sink(path: Path, append: Boolean): CoroutineRawSink =
        withContext(IoDispatcher) {
            MingwCoroutineRawSink(
                openHandle(
                    path = path,
                    desiredAccess = if (append) FILE_APPEND_DATA.toUInt() else GENERIC_WRITE.toUInt(),
                    creationDisposition = if (append) OPEN_ALWAYS.toUInt() else CREATE_ALWAYS.toUInt(),
                    flagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
                    operation = "Open sink",
                ),
            )
        }
}

private typealias WindowsHandle = CPointer<out CPointed>

private fun windowsMetadataOrNull(path: Path): FileMetadata? = memScoped {
    val findData = alloc<WIN32_FIND_DATAW>()
    val handle = FindFirstFileW(path.windowsPath(), findData.ptr)
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        val error = GetLastError().toInt()
        if (error.isMissingPathError()) return@memScoped null
        throw pathFailure("Read metadata", path, error)
    }

    try {
        findData.toFileMetadata()
    } finally {
        FindClose(handle)
    }
}

private fun WIN32_FIND_DATAW.toFileMetadata(): FileMetadata {
    val directory = dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() != 0u
    val size = (nFileSizeHigh.toLong() shl Int.SIZE_BITS) or nFileSizeLow.toLong()
    return FileMetadata(
        isRegularFile = !directory,
        isDirectory = directory,
        size = if (directory) -1L else size,
    )
}

private fun listDirectory(directory: Path): List<Path> = memScoped {
    val metadata = windowsMetadataOrNull(directory) ?: throw missingFile(directory)
    if (!metadata.isDirectory) throw IOException("Not a directory: $directory")

    val findData = alloc<WIN32_FIND_DATAW>()
    val handle = FindFirstFileW(directorySearchPattern(directory), findData.ptr)
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        throw pathFailure("List", directory, GetLastError().toInt())
    }

    try {
        buildList {
            while (true) {
                val child = findData.cFileName.toKStringFromUtf16()
                if (child != "." && child != "..") add(Path(directory, child))

                if (FindNextFileW(handle, findData.ptr) != 0) continue
                val error = GetLastError().toInt()
                if (error == ERROR_NO_MORE_FILES) break
                throw pathFailure("List", directory, error)
            }
        }
    } finally {
        FindClose(handle)
    }
}

private fun directorySearchPattern(directory: Path): String =
    directory.windowsPath().let { value ->
        if (value.endsWith('\\') || value.endsWith('/')) "$value*" else "$value\\*"
    }

private fun openHandle(
    path: Path,
    desiredAccess: UInt,
    creationDisposition: UInt,
    flagsAndAttributes: UInt,
    operation: String,
): WindowsHandle {
    val handle = CreateFileW(
        path.windowsPath(),
        desiredAccess,
        (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
        null,
        creationDisposition,
        flagsAndAttributes,
        null,
    )
    if (handle != null && handle != INVALID_HANDLE_VALUE) return handle

    val error = GetLastError().toInt()
    if (error.isMissingPathError()) {
        throw FileNotFoundException("File does not exist: $path (Windows error $error)")
    }
    throw pathFailure(operation, path, error)
}

private fun finalPath(handle: WindowsHandle): String {
    var capacity = 260
    while (true) {
        val resolved = memScoped {
            val buffer = allocArray<UShortVar>(capacity)
            val length = GetFinalPathNameByHandleW(handle, buffer, capacity.toUInt(), VOLUME_NAME_DOS.toUInt())
            when {
                length == 0u -> throw handleFailure("Resolve path", GetLastError().toInt())
                length < capacity.toUInt() -> buffer.toKStringFromUtf16()
                else -> null
            }
        }
        if (resolved != null) return resolved
        capacity *= 2
    }
}

private fun String.fromWindowsNamespace(): String =
    when {
        startsWith("\\\\?\\UNC\\") -> "\\\\" + removePrefix("\\\\?\\UNC\\")
        startsWith("\\\\?\\") -> removePrefix("\\\\?\\")
        else -> this
    }

private fun Path.windowsPath(): String =
    toString().replace('/', '\\')

private class MingwCoroutineRawSource(
    private val handle: WindowsHandle,
) : CoroutineRawSource {
    private var closed: Boolean = false

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "Source is closed." }
        if (byteCount == 0L) return 0L

        return withContext(IoDispatcher) {
            val bytes = ByteArray(minOf(byteCount, CoroutineIoSegmentByteCount.toLong()).toInt())
            val read = memScoped {
                val count = alloc<UIntVar>()
                val success = bytes.usePinned { pinned ->
                    ReadFile(handle, pinned.addressOf(0), bytes.size.toUInt(), count.ptr, null)
                }
                if (success == 0) throw handleFailure("Read", GetLastError().toInt())
                count.value.toInt()
            }
            if (read == 0) -1L else {
                sink.write(bytes, 0, read)
                read.toLong()
            }
        }
    }

    override suspend fun close() {
        if (closed) return
        withContext(IoDispatcher) {
            if (closed) return@withContext
            closed = true
            closeHandle(handle, "Close source")
        }
    }
}

private class MingwCoroutineRawSink(
    private val handle: WindowsHandle,
) : CoroutineRawSink {
    private var closed: Boolean = false

    override suspend fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "Sink is closed." }

        withContext(IoDispatcher) {
            var remaining = byteCount
            while (remaining > 0L) {
                val chunk = source.readByteArray(
                    minOf(remaining, CoroutineIoSegmentByteCount.toLong()).toInt(),
                )
                var offset = 0
                while (offset < chunk.size) {
                    val written = memScoped {
                        val count = alloc<UIntVar>()
                        val success = chunk.usePinned { pinned ->
                            WriteFile(
                                handle,
                                pinned.addressOf(offset),
                                (chunk.size - offset).toUInt(),
                                count.ptr,
                                null,
                            )
                        }
                        if (success == 0) throw handleFailure("Write", GetLastError().toInt())
                        count.value.toInt()
                    }
                    if (written == 0) throw IOException("Write completed without writing bytes.")
                    offset += written
                }
                remaining -= chunk.size
            }
        }
    }

    override suspend fun flush() {
        check(!closed) { "Sink is closed." }
        withContext(IoDispatcher) {
            if (FlushFileBuffers(handle) == 0) throw handleFailure("Flush", GetLastError().toInt())
        }
    }

    override suspend fun close() {
        if (closed) return
        withContext(IoDispatcher) {
            if (closed) return@withContext
            closed = true
            closeHandle(handle, "Close sink")
        }
    }
}

private fun closeHandle(handle: WindowsHandle, operation: String) {
    if (CloseHandle(handle) == 0) throw handleFailure(operation, GetLastError().toInt())
}

private fun Int.isMissingPathError(): Boolean =
    this == ERROR_FILE_NOT_FOUND || this == ERROR_PATH_NOT_FOUND

private fun missingFile(path: Path): FileNotFoundException =
    FileNotFoundException("File does not exist: $path")

private fun pathFailure(operation: String, path: Path, error: Int): IOException =
    IOException("$operation failed for $path: Windows error $error.")

private fun handleFailure(operation: String, error: Int): IOException =
    IOException("$operation failed: Windows error $error.")
