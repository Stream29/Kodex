package io.github.stream29.kodex.backend

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import kotlinx.io.files.Path

private class JvmBackendPlatformLock(
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : BackendPlatformLock {
    override fun close() {
        try { lock.release() } finally { file.close() }
    }
}

internal actual fun openBackendPlatformLock(path: String): BackendPlatformLock? {
    val file = RandomAccessFile(path, "rw")
    try {
        if (File(path).toPath().fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(File(path).toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
        val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (lock == null) { file.close(); return null }
        return JvmBackendPlatformLock(file, lock)
    } catch (failure: Throwable) {
        file.close()
        throw failure
    }
}

public actual fun randomBackendPassword(): String =
    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }

public actual fun launchIndependentBackend(home: Path): Long {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val command = listOf(java, "-cp", System.getProperty("java.class.path"), "io.github.stream29.kodex.cli.app.MainKt", "backend", "start")
    return ProcessBuilder(command).directory(File(home.toString()))
        .redirectInput(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(File(home.toString(), "backend-startup.log")))
        .redirectErrorStream(true).start().pid()
}

public actual fun setBackendWorkingDirectory(home: Path) {
    // JVM operations always receive explicit Home/working-directory paths.
    System.setProperty("user.dir", home.toString())
}
public actual fun installBackendSignals() {}
public actual fun backendStopSignalReceived(): Boolean = false
public actual fun backendProcessAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
public actual fun exitKodexProcess(code: Int): Nothing = kotlin.system.exitProcess(code)
