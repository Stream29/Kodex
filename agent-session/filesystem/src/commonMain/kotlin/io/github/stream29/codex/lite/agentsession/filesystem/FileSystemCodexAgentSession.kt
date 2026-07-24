@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.codex.lite.agentsession.filesystem

import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionRepository
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.codex.lite.utils.filesystemlease.FileSystemLease
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.newCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal class FileSystemCodexAgentSession(
    directory: Path,
    fileSystem: CoroutineFileSystem,
    valueCacheSize: Int,
    scope: CoroutineScope,
    override val storage: MutableCodexAgentStorage,
) : CodexAgentSession, CoroutineScope by scope {
    override val subagents: CodexSessionRepository = FileSystemSubagentRepository(
        directory = Path(directory, SubagentsDirectory),
        fileSystem = fileSystem,
        valueCacheSize = valueCacheSize,
        scope = CoroutineScope(scope.newCoroutineContext(SupervisorJob(scope.coroutineContext[Job]))),
    )
}

private class FileSystemSubagentRepository(
    private val directory: Path,
    private val fileSystem: CoroutineFileSystem,
    private val valueCacheSize: Int,
    scope: CoroutineScope,
) : CodexSessionRepository, CoroutineScope by scope {
    private val entriesMutex: Mutex = Mutex()

    override suspend fun list(): List<Int> = entriesMutex.withLock {
        requireActive()
        childDirectories(directory, fileSystem).map { (entryIndex) -> entryIndex }
    }

    override suspend fun create(): Int = entriesMutex.withLock {
        requireActive()
        val entryIndex = smallestMissing(childDirectories(directory, fileSystem).map { (index) -> index })
        createEmptyFileSystemAgentSessionNode(Path(directory, entryIndex.toString()), fileSystem)
        entryIndex
    }

    override suspend fun open(entryIndex: Int): CodexAgentSession = entriesMutex.withLock {
        requireActive()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        val agentDirectory = Path(directory, entryIndex.toString())
        require(fileSystem.metadataOrNull(agentDirectory)?.isDirectory == true) {
            "Agent entry does not exist: $entryIndex"
        }
        val agentScope = CoroutineScope(newCoroutineContext(SupervisorJob(coroutineContext[Job])))
        FileSystemCodexAgentSession(
            directory = agentDirectory,
            fileSystem = fileSystem,
            valueCacheSize = valueCacheSize,
            scope = agentScope,
            storage = FileSystemAgentStorage(agentDirectory, fileSystem)
                .cached(agentScope, valueCacheSize),
        )
    }

    override suspend fun delete(entryIndex: Int) {
        entriesMutex.withLock {
            requireActive()
            require(entryIndex >= 0) { "Session entry index must be non-negative." }
            val agentDirectory = Path(directory, entryIndex.toString())
            require(fileSystem.metadataOrNull(agentDirectory)?.isDirectory == true) {
                "Agent entry does not exist: $entryIndex"
            }
            deleteRecursively(agentDirectory, fileSystem)
        }
    }

    private fun requireActive() {
        check(coroutineContext[Job]?.isActive == true) { "Subagent repository is closed." }
    }
}

internal suspend fun CoroutineScope.FileSystemCodexAgentSession(
    directory: Path,
    fileSystem: CoroutineFileSystem,
    valueCacheSize: Int,
): CodexAgentSession {
    val scope = CoroutineScope(newCoroutineContext(SupervisorJob(coroutineContext[Job])))
    val lease = try {
        scope.FileSystemLease(
            lockPath = Path(directory, LockFile),
            fileSystem = fileSystem,
            duration = SessionLeaseDuration,
        )
    } catch (failure: Throwable) {
        scope.coroutineContext[Job]?.cancelAndJoin()
        throw failure
    }
    try {
        val session = FileSystemCodexAgentSession(
            directory = directory,
            fileSystem = fileSystem,
            valueCacheSize = valueCacheSize,
            scope = scope,
            storage = FileSystemAgentStorage(directory, fileSystem).cached(scope, valueCacheSize),
        )
        scope.coroutineContext[Job]?.invokeOnCompletion { lease.close() }
        return session
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            scope.coroutineContext[Job]?.cancelAndJoin()
            lease.close()
        }
        throw failure
    }
}
