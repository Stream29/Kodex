@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/**
 * Owns a stable kernel lock inode. Metadata is a private atomic sibling in the logical lock
 * directory, never an atomic replacement/unlink of the locked inode itself.
 */
public expect class BackendInstance : AutoCloseable {
    public suspend fun publish(endpoint: BackendEndpoint)

    /** Caller must finish service/resource cleanup before releasing this handle. */
    override fun close()

    public companion object {
        /** Last private record, including after exit. Never use this method as live discovery. */
        public suspend fun readRecord(home: Path): BackendEndpoint?
        public suspend fun acquire(home: Path): BackendInstance

        /** Read-only discovery. Never deletes stale metadata or takes ownership of an alive backend. */
        public suspend fun discover(home: Path): BackendEndpoint?
    }
}

public expect fun randomBackendPassword(): String
/** Starts the internal detached CLI entry in [home], streaming to a caller-owned private file. */
public expect fun launchIndependentBackend(home: Path, startupOutput: Path): Long
public expect fun setBackendWorkingDirectory(home: Path)
/** Applies allocator settings in the daemon process, before opening daemon resources. */
public expect fun configureBackendAllocator()

/** Installs the stop flag checked by the local Web/App foreground entrypoints. */
public expect fun installBackendSignals()
public expect fun backendStopSignalReceived(): Boolean

/**
 * Temporarily owns the process stop handlers. Install after the server's own handlers
 * and before publishing readiness. An OS stop signal invokes [onSignal] at most once.
 * When [block] or its owner ends, cancel and drain the waiter before restoring the handlers.
 */
public expect suspend fun <R> withBackendStopSignals(
    onSignal: suspend () -> Unit,
    block: suspend CoroutineScope.() -> R,
): R
public expect fun backendProcessAlive(pid: Long): Boolean
public expect fun exitKodexProcess(code: Int): Nothing
