@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.coroutines.await
import kotlinx.io.IOException
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsModule
import kotlin.js.JsNonModule
import kotlin.js.Promise

public actual val SystemCoroutineFileSystem: CoroutineFileSystem =
    NodeCoroutineFileSystem

private object NodeCoroutineFileSystem : CoroutineFileSystem {
    override suspend fun exists(path: Path): Boolean =
        try {
            fsPromises.stat(path.toString()).await()
            true
        } catch (error: Throwable) {
            if (error.nodeErrorCode == "ENOENT") false else throw IOException("Stat failed for $path", error)
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
                fsPromises.rmdir(path.toString()).await()
            } else {
                fsPromises.rm(path.toString()).await()
            }
        } catch (error: Throwable) {
            throw IOException("Delete failed for $path", error)
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
            fsPromises.mkdir(path.toString(), recursiveMkdirOptions()).await()
        } catch (error: Throwable) {
            throw IOException("Create directories failed for $path", error)
        }
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        if (!exists(source)) {
            throw FileNotFoundException("Source does not exist: $source")
        }
        try {
            fsPromises.rename(source.toString(), destination.toString()).await()
        } catch (error: Throwable) {
            throw IOException("Move failed from $source to $destination", error)
        }
    }

    override suspend fun metadataOrNull(path: Path): FileMetadata? {
        val stats = try {
            fsPromises.stat(path.toString()).await().unsafeCast<NodeStats>()
        } catch (error: Throwable) {
            if (error.nodeErrorCode == "ENOENT") return null
            throw IOException("Stat failed for $path", error)
        }
        val isFile = stats.isFile()
        return FileMetadata(
            isRegularFile = isFile,
            isDirectory = stats.isDirectory(),
            size = if (isFile) stats.size.toLong() else -1L,
        )
    }

    override suspend fun resolve(path: Path): Path {
        if (!exists(path)) {
            throw FileNotFoundException(path.toString())
        }
        return try {
            Path(fsPromises.realpath(path.toString()).await().unsafeCast<String>())
        } catch (error: Throwable) {
            throw IOException("Resolve failed for $path", error)
        }
    }

    override suspend fun list(directory: Path): Collection<Path> {
        val metadata = metadataOrNull(directory) ?: throw FileNotFoundException(directory.toString())
        if (!metadata.isDirectory) {
            throw IOException("Not a directory: $directory")
        }
        return try {
            fsPromises.readdir(directory.toString()).await()
                .unsafeCast<Array<String>>()
                .map { Path(directory, it) }
        } catch (error: Throwable) {
            throw IOException("List failed for $directory", error)
        }
    }

    override suspend fun readString(path: Path): String =
        try {
            fsPromises.readFile(path.toString(), "utf8").await().unsafeCast<String>()
        } catch (error: Throwable) {
            if (error.nodeErrorCode == "ENOENT") {
                throw FileNotFoundException("File does not exist: $path")
            }
            throw IOException("Read failed for $path", error)
        }

    override suspend fun writeString(path: Path, content: String, append: Boolean) {
        try {
            if (append) {
                fsPromises.appendFile(path.toString(), content, "utf8").await()
            } else {
                fsPromises.writeFile(path.toString(), content, "utf8").await()
            }
        } catch (error: Throwable) {
            throw IOException("Write failed for $path", error)
        }
    }
}

private val Throwable.nodeErrorCode: String?
    get() = codeOf(this)

@Suppress("UNUSED_PARAMETER")
private fun codeOf(error: Throwable): String? =
    js("error.code") as? String

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
private fun recursiveMkdirOptions(): MkdirOptions =
    js("({ recursive: true })")

@JsModule("node:fs/promises")
@JsNonModule
private external val fsPromises: FsPromises

private external interface FsPromises {
    fun stat(path: String): Promise<JsAny?>
    fun rm(path: String): Promise<JsAny?>
    fun rmdir(path: String): Promise<JsAny?>
    fun mkdir(path: String, options: MkdirOptions): Promise<JsAny?>
    fun rename(source: String, destination: String): Promise<JsAny?>
    fun realpath(path: String): Promise<JsAny?>
    fun readdir(path: String): Promise<JsAny?>
    fun readFile(path: String, encoding: String): Promise<JsAny?>
    fun writeFile(path: String, content: String, encoding: String): Promise<JsAny?>
    fun appendFile(path: String, content: String, encoding: String): Promise<JsAny?>
}

private external interface MkdirOptions : JsAny

private external interface NodeStats : JsAny {
    val size: Double
    fun isFile(): Boolean
    fun isDirectory(): Boolean
}
