@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.backend

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
public expect fun launchIndependentBackend(home: Path): Long
public expect fun setBackendWorkingDirectory(home: Path)
public expect fun installBackendSignals()
public expect fun backendStopSignalReceived(): Boolean
public expect fun backendProcessAlive(pid: Long): Boolean
public expect fun exitKodexProcess(code: Int): Nothing
