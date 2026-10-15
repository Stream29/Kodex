package io.github.stream29.kodex.backend

import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json

/**
 * Owns a stable kernel lock inode. Metadata is a private atomic sibling in the logical lock
 * directory, never an atomic replacement/unlink of the locked inode itself.
 */
public class BackendInstance private constructor(
    private val lock: BackendPlatformLock,
    private val directory: Path,
) : AutoCloseable {
    public suspend fun publish(endpoint: BackendEndpoint) {
        val target = Path(directory, "connection.json")
        val temporary = Path(directory, ".connection.${endpoint.instanceId}.tmp")
        var primary: Throwable? = null
        try {
            SystemCoroutineFileSystem.writePrivateString(
                temporary, EndpointJson.encodeToString(BackendEndpoint.serializer(), endpoint), mustCreate = true,
            )
            SystemCoroutineFileSystem.atomicMove(temporary, target)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try { withContext(NonCancellable) { SystemCoroutineFileSystem.delete(temporary, mustExist = false) } }
            catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
        }
    }

    /** Caller must finish service/resource cleanup before releasing this handle. */
    override fun close() { lock.close() }

    public companion object {
        /** Last private record, including after exit. Never use this method as live discovery. */
        public suspend fun readRecord(home: Path): BackendEndpoint? {
            val path = Path(home, ".locks", "backend", "connection.json")
            if (!SystemCoroutineFileSystem.exists(path)) return null
            return EndpointJson.decodeFromString(BackendEndpoint.serializer(), SystemCoroutineFileSystem.readString(path))
        }

        public suspend fun acquire(home: Path): BackendInstance {
            val directory = Path(home, ".locks", "backend")
            SystemCoroutineFileSystem.createDirectories(directory)
            val lock = openBackendPlatformLock(Path(directory, "instance.lock").toString())
                ?: error("A backend already holds this user's instance lock.")
            return BackendInstance(lock, directory)
        }

        /** Read-only discovery. Never deletes stale metadata or takes ownership of an alive backend. */
        public suspend fun discover(home: Path): BackendEndpoint? {
            val directory = Path(home, ".locks", "backend")
            val path = Path(directory, "instance.lock")
            if (!SystemCoroutineFileSystem.exists(path)) return null
            val probe = openBackendPlatformLock(path.toString())
            if (probe != null) {
                probe.close()
                return null
            }
            try {
                return checkNotNull(readRecord(home)) { "The locked backend has not published a connection." }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error("A locked backend is unavailable; it will not be replaced.")
            }
        }
    }
}

internal interface BackendPlatformLock {
    fun close()
}

internal expect fun openBackendPlatformLock(path: String): BackendPlatformLock?
public expect fun randomBackendPassword(): String
public expect fun launchIndependentBackend(home: Path): Long
public expect fun setBackendWorkingDirectory(home: Path)
public expect fun installBackendSignals()
public expect fun backendStopSignalReceived(): Boolean
public expect fun backendProcessAlive(pid: Long): Boolean
public expect fun exitKodexProcess(code: Int): Nothing

private val EndpointJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
