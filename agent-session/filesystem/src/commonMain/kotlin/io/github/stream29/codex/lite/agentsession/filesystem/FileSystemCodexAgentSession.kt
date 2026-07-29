package io.github.stream29.codex.lite.agentsession.filesystem

import io.github.stream29.codex.lite.agentruntime.composite.CompositeAgentRuntime
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionRepository
import io.github.stream29.codex.lite.agentsession.composition.CodexAgentDependencies
import io.github.stream29.codex.lite.agentsession.composition.buildAgentRuntime
import io.github.stream29.codex.lite.agentsession.multiagent.AgentPathResolverImpl
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.filesystem.FileSystemAgentStorage
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import io.github.stream29.codex.lite.utils.filesystemlease.FileSystemLease
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    dependencies: CodexAgentDependencies,
    state: CodexAgentStateContract,
    createAgentPathResolver: (CodexAgentSession) -> AgentPathResolver,
) : CodexAgentSession, CoroutineScope by scope {
    private val agentPathResolver: AgentPathResolver =
        createAgentPathResolver(this)

    override val subagents: CodexSessionRepository = FileSystemSubagentRepository(
        directory = Path(directory, SubagentsDirectory),
        fileSystem = fileSystem,
        valueCacheSize = valueCacheSize,
        scope = scope.supervisorChildScope(),
        dependencies = dependencies,
        agentPathResolver = agentPathResolver,
    )

    override val runtime: CompositeAgentRuntime = state.buildAgentRuntime(
        dependencies = dependencies,
        agentPathResolver = agentPathResolver,
    )
}

private class FileSystemSubagentRepository(
    private val directory: Path,
    private val fileSystem: CoroutineFileSystem,
    private val valueCacheSize: Int,
    scope: CoroutineScope,
    private val dependencies: CodexAgentDependencies,
    private val agentPathResolver: AgentPathResolver,
) : CodexSessionRepository, CoroutineScope by scope {
    private val entriesMutex: Mutex = Mutex()
    private val openSessions: MutableMap<Int, FileSystemCodexAgentSession> = mutableMapOf()

    init {
        coroutineContext[Job]?.invokeOnCompletion { openSessions.clear() }
    }

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
        openSessions[entryIndex]?.let { session ->
            if (session.coroutineContext[Job]?.isActive == true) return@withLock session
            openSessions.remove(entryIndex)
        }
        val agentScope = supervisorChildScope()
        val storage = FileSystemAgentStorage(agentDirectory, fileSystem)
            .cached(agentScope, valueCacheSize)
        val session = try {
            FileSystemCodexAgentSession(
                directory = agentDirectory,
                fileSystem = fileSystem,
                valueCacheSize = valueCacheSize,
                scope = agentScope,
                storage = storage,
                dependencies = dependencies,
                state = agentScope.CodexAgentState(
                    client = dependencies.client,
                    storage = storage,
                    contextSettings = dependencies.contextSettings,
                    mcpService = dependencies.mcpService,
                ),
                createAgentPathResolver = { agentPathResolver },
            )
        } catch (failure: Throwable) {
            withContext(NonCancellable) { agentScope.cancelAndJoin() }
            throw failure
        }
        openSessions[entryIndex] = session
        session
    }

    override suspend fun delete(entryIndex: Int) {
        entriesMutex.withLock {
            requireActive()
            require(entryIndex >= 0) { "Session entry index must be non-negative." }
            val agentDirectory = Path(directory, entryIndex.toString())
            require(fileSystem.metadataOrNull(agentDirectory)?.isDirectory == true) {
                "Agent entry does not exist: $entryIndex"
            }
            withContext(NonCancellable) {
                openSessions.remove(entryIndex)?.cancelAndJoin()
                deleteRecursively(agentDirectory, fileSystem)
            }
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
    dependencies: CodexAgentDependencies,
): CodexAgentSession {
    val scope = supervisorChildScope()
    val lease = try {
        scope.FileSystemLease(
            lockPath = Path(directory, LockFile),
            fileSystem = fileSystem,
            duration = SessionLeaseDuration,
        )
    } catch (failure: Throwable) {
        scope.cancelAndJoin()
        throw failure
    }
    try {
        val storage = FileSystemAgentStorage(directory, fileSystem).cached(scope, valueCacheSize)
        val session = FileSystemCodexAgentSession(
            directory = directory,
            fileSystem = fileSystem,
            valueCacheSize = valueCacheSize,
            scope = scope,
            storage = storage,
            dependencies = dependencies,
            state = scope.CodexAgentState(
                client = dependencies.client,
                storage = storage,
                contextSettings = dependencies.contextSettings,
                mcpService = dependencies.mcpService,
            ),
            createAgentPathResolver = { rootSession ->
                AgentPathResolverImpl(rootSession)
            },
        )
        scope.coroutineContext[Job]?.invokeOnCompletion { lease.close() }
        return session
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            scope.cancelAndJoin()
            lease.close()
        }
        throw failure
    }
}
