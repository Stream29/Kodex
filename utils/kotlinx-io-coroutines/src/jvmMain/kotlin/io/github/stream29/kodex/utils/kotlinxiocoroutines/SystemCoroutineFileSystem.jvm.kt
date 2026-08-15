package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.io.asSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

public actual val SystemCoroutineFileSystem: CoroutineFileSystem =
    BlockingCoroutineFileSystem(
        delegate = SystemFileSystem,
        exclusiveSink = ::exclusiveSink,
        fingerprint = ::fingerprintOrNull,
        protectPrivateFile = ::protectPrivateFile,
    )

private fun fingerprintOrNull(path: Path): FileFingerprint? = try {
    val attributes = Files.readAttributes(
        java.nio.file.Path.of(path.toString()),
        BasicFileAttributes::class.java,
    )
    val modified = attributes.lastModifiedTime().toInstant()
    FileFingerprint(
        size = if (attributes.isDirectory) -1L else attributes.size(),
        lastModifiedAtNanoseconds = modified.epochSecond * NanosecondsPerSecond + modified.nano,
        fileKey = attributes.fileKey()?.toString(),
    )
} catch (_: NoSuchFileException) {
    null
}

private fun exclusiveSink(path: Path, append: Boolean): CoroutineRawSink =
    BlockingCoroutineRawSink(
        Files.newOutputStream(
            java.nio.file.Path.of(path.toString()),
            CREATE_NEW,
            if (append) APPEND else WRITE,
        ).asSink(),
    )

private fun protectPrivateFile(path: Path) {
    try {
        Files.setPosixFilePermissions(
            java.nio.file.Path.of(path.toString()),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
    } catch (_: UnsupportedOperationException) {
        // Windows and other non-POSIX filesystems rely on their inherited ACL.
    }
}

private const val NanosecondsPerSecond: Long = 1_000_000_000L
