package io.github.stream29.codex.lite.agentsession.filesystem

import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionRepository
import io.github.stream29.codex.lite.agentsession.composition.CodexAgentDependencies
import io.github.stream29.codex.lite.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.codex.lite.agentstorage.filesystem.ofEmpty
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.filesystemlease.FileSystemLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Filesystem-backed recursive session repository. */
public class FileSystemCodexSessionRepository internal constructor(
    scope: CoroutineScope,
    root: Path,
    private val fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    private val valueCacheSize: Int = 256,
    private val dependencies: CodexAgentDependencies,
) :
    CodexSessionRepository,
    CoroutineScope by scope {
    private val openRootsMutex = Mutex()
    private val openRoots: MutableMap<Int, CodexAgentSession> = mutableMapOf()
    private val sessionsRoot = Path(root, SessionsDirectory)

    init {
        coroutineContext[Job]?.invokeOnCompletion { openRoots.clear() }
    }

    override suspend fun list(): List<Int> {
        requireOpen()
        return sessionDirectories().map { (index) -> index }
    }

    override suspend fun create(): Int {
        requireOpen()
        val index = smallestMissing(sessionDirectories().map { (index) -> index })
        val directory = sessionDirectory(index)
        FileSystemAgentStorage.ofEmpty(directory, fileSystem)
        fileSystem.createDirectories(Path(directory, SubagentsDirectory), mustCreate = true)
        return index
    }

    override suspend fun open(
        entryIndex: Int,
    ): CodexAgentSession {
        requireOpen()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        return openRootsMutex.withLock {
            openRoots[entryIndex]?.let { root ->
                if (root.coroutineContext[Job]?.isActive == true) return@withLock root
                openRoots.remove(entryIndex)
            }
            val directory = sessionDirectory(entryIndex)
            require(fileSystem.metadataOrNull(directory)?.isDirectory == true) {
                "Session directory does not exist: $directory"
            }
            FileSystemCodexAgentSession(
                directory = directory,
                fileSystem = fileSystem,
                valueCacheSize = valueCacheSize,
                dependencies = dependencies,
            ).also { root ->
                openRoots[entryIndex] = root
            }
        }
    }

    override suspend fun delete(entryIndex: Int) {
        requireOpen()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        val openRoot = openRootsMutex.withLock { openRoots.remove(entryIndex) }
        withContext(NonCancellable) {
            openRoot?.coroutineContext?.get(Job)?.cancelAndJoin()
        }
        val directory = sessionDirectory(entryIndex)
        val lease = acquireSessionLeaseAfterClose(entryIndex)
        try {
            deleteDirectoryContents(directory, except = LockFile)
        } finally {
            withContext(NonCancellable) {
                lease.close()
                lease.coroutineContext[Job]?.join()
            }
        }
        fileSystem.delete(directory, mustExist = false)
    }

    private suspend fun acquireSessionLeaseAfterClose(
        sessionIndex: Int,
    ): FileSystemLease {
        var delayDuration = 1.milliseconds
        while (true) {
            try {
                return FileSystemLease(
                    lockPath = Path(sessionDirectory(sessionIndex), LockFile),
                    fileSystem = fileSystem,
                    duration = SessionLeaseDuration,
                )
            } catch (_: io.github.stream29.codex.lite.utils.filesystemlease.FileSystemLeaseInUseException) {
                delay(delayDuration)
                delayDuration = (delayDuration * 2).coerceAtMost(100.milliseconds)
            }
        }
    }

    private suspend fun sessionDirectories(): List<Pair<Int, Path>> =
        fileSystem.list(sessionsRoot)
            .filterNot { path -> path.name.startsWith(".") }
            .map { directory ->
                val index = directory.name.toInt()
                require(index >= 0) { "Session index must be non-negative: $directory" }
                require(fileSystem.metadataOrNull(directory)?.isDirectory == true) {
                    "Session entry is not a directory: $directory"
                }
                index to directory
            }
            .sortedBy { (index) -> index }

    private fun sessionDirectory(index: Int): Path =
        Path(sessionsRoot, index.toString())

    private suspend fun deleteRecursively(path: Path) {
        val metadata = fileSystem.metadataOrNull(path) ?: return
        if (metadata.isDirectory) {
            fileSystem.list(path).forEach { child -> deleteRecursively(child) }
        }
        fileSystem.delete(path, mustExist = false)
    }

    private suspend fun deleteDirectoryContents(
        directory: Path,
        except: String,
    ) {
        fileSystem.list(directory)
            .filterNot { path -> path.name == except }
            .forEach { path -> deleteRecursively(path) }
    }

    private fun requireOpen() {
        check(coroutineContext[Job]?.isActive == true) { "Session repository is closed." }
    }

}

internal fun smallestMissing(values: List<Int>): Int {
    var candidate = 0
    values.sorted().forEach { value ->
        if (value == candidate) candidate += 1
    }
    return candidate
}

internal suspend fun childDirectories(
    directory: Path,
    fileSystem: CoroutineFileSystem,
): List<Pair<Int, Path>> =
    fileSystem.list(directory)
        .filterNot { path -> path.name.startsWith(".") }
        .mapNotNull { child ->
            val index = child.name.toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: return@mapNotNull null
            if (fileSystem.metadataOrNull(child)?.isDirectory != true) return@mapNotNull null
            index to child
        }
        .sortedBy { (index) -> index }

internal suspend fun createEmptyFileSystemAgentSessionNode(
    directory: Path,
    fileSystem: CoroutineFileSystem,
) {
    FileSystemAgentStorage.ofEmpty(directory, fileSystem)
    fileSystem.createDirectories(Path(directory, SubagentsDirectory), mustCreate = true)
}

internal suspend fun deleteRecursively(
    path: Path,
    fileSystem: CoroutineFileSystem,
) {
    val metadata = fileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        fileSystem.list(path).forEach { child -> deleteRecursively(child, fileSystem) }
    }
    fileSystem.delete(path, mustExist = false)
}

/** Creates a filesystem session repository, initializing its root layout when needed. */
public suspend fun CoroutineScope.FileSystemCodexSessionRepository(
    root: Path,
    dependencies: CodexAgentDependencies,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    valueCacheSize: Int = 256,
): FileSystemCodexSessionRepository {
    fileSystem.createDirectories(root)
    fileSystem.createDirectories(Path(root, SessionsDirectory))
    return FileSystemCodexSessionRepository(
        scope = supervisorChildScope(),
        root = root,
        fileSystem = fileSystem,
        valueCacheSize = valueCacheSize,
        dependencies = dependencies,
    )
}

private const val SessionsDirectory: String = "sessions"
internal const val SubagentsDirectory: String = "subagents"
internal const val LockFile: String = "lock.json"
internal val SessionLeaseDuration: Duration = 30.seconds
