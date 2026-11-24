@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.backend

import io.github.stream29.kodex.backend.cinterop.kodex_backend_lock
import io.github.stream29.kodex.backend.cinterop.kodex_backend_unlock
import io.github.stream29.kodex.backend.cinterop.kodex_backend_random
import io.github.stream29.kodex.backend.cinterop.kodex_backend_spawn
import io.github.stream29.kodex.backend.cinterop.kodex_backend_chdir
import io.github.stream29.kodex.backend.cinterop.kodex_backend_configure_allocator
import io.github.stream29.kodex.backend.cinterop.kodex_backend_install_signals
import io.github.stream29.kodex.backend.cinterop.kodex_backend_uninstall_signals
import io.github.stream29.kodex.backend.cinterop.kodex_backend_alive
import io.github.stream29.kodex.backend.cinterop.kodex_frontend_install_signals
import io.github.stream29.kodex.backend.cinterop.kodex_frontend_stop_signal
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.io.files.Path

private class NativeBackendPlatformLock(private var descriptor: Long) : BackendPlatformLock {
    override fun close() {
        if (descriptor >= 0) {
            val current = descriptor
            descriptor = -1
            check(kodex_backend_unlock(current) == 0) { "Backend instance lock release failed." }
        }
    }
}

internal actual fun openBackendPlatformLock(path: String, create: Boolean): BackendPlatformLock? {
    val descriptor = kodex_backend_lock(path, if (create) 1 else 0)
    if (descriptor == -1L) return null
    if (descriptor < 0) throw IOException("Cannot inspect the backend instance lock.")
    return NativeBackendPlatformLock(descriptor)
}

public actual fun randomBackendPassword(): String {
    val bytes = ByteArray(32)
    val result = bytes.usePinned { kodex_backend_random(it.addressOf(0), bytes.size) }
    check(result == 0) { "OS random source failed." }
    val digits = "0123456789abcdef"
    return buildString(64) {
        bytes.forEach { byte -> val n = byte.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) }
    }
}

public actual fun launchIndependentBackend(home: Path, startupOutput: Path): Long {
    val pid = kodex_backend_spawn(home.toString(), startupOutput.toString())
    check(pid > 0) { "Unable to start an independent backend process." }
    return pid
}
public actual fun setBackendWorkingDirectory(home: Path) {
    check(kodex_backend_chdir(home.toString()) == 0) { "Cannot enter Kodex Home." }
}

public actual fun configureBackendAllocator() { kodex_backend_configure_allocator() }
public actual fun installBackendSignals() { kodex_frontend_install_signals() }
public actual fun backendStopSignalReceived(): Boolean = kodex_frontend_stop_signal() != 0

public actual suspend fun <R> withBackendStopSignals(
    onSignal: suspend () -> Unit,
    block: suspend CoroutineScope.() -> R,
): R {
    check(kodex_backend_install_signals() == 0) { "Cannot install backend stop signal handlers." }
    return AutoCloseable {
        check(kodex_backend_uninstall_signals() == 0) { "Cannot restore previous backend stop signal handlers." }
    }.use {
        coroutineScope {
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                awaitBackendStopSignal()
                onSignal()
            }
            try {
                block()
            } finally {
                waiter.cancel()
            }
        }
    }
}

public actual fun backendProcessAlive(pid: Long): Boolean = kodex_backend_alive(pid) != 0
public actual fun exitKodexProcess(code: Int): Nothing = kotlin.system.exitProcess(code)
