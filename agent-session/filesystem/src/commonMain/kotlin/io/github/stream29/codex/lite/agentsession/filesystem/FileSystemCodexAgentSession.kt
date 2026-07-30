package io.github.stream29.codex.lite.agentsession.filesystem

import io.github.stream29.codex.lite.agentruntime.contract.AgentRuntime
import io.github.stream29.codex.lite.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.codex.lite.agentruntime.impl.buildSubagentRuntime
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentDependencies
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionRepository
import io.github.stream29.codex.lite.agentsession.contract.CodexSessionEntry
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

private typealias AgentRuntimeBuilder = (
    CodexAgentStateContract,
    CodexAgentDependencies,
    AgentPathResolver,
) -> AgentRuntime

internal class FileSystemCodexAgentSession(
    directory: Path,
    fileSystem: CoroutineFileSystem,
    valueCacheSize: Int,
    scope: CoroutineScope,
    override val storage: MutableCodexAgentStorage,
    dependencies: CodexAgentDependencies,
    state: CodexAgentStateContract,
    createAgentPathResolver: (CodexAgentSession) -> AgentPathResolver,
    runtimeBuilder: AgentRuntimeBuilder,
    initialSubagentEntries: List<Int>,
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
        initialEntries = initialSubagentEntries,
    )

    override val runtime: AgentRuntime = runtimeBuilder(state, dependencies, agentPathResolver)
}

private class FileSystemSubagentRepository(
    private val directory: Path,
    private val fileSystem: CoroutineFileSystem,
    private val valueCacheSize: Int,
    scope: CoroutineScope,
    private val dependencies: CodexAgentDependencies,
    private val agentPathResolver: AgentPathResolver,
    initialEntries: List<Int>,
) : CodexSessionRepository, CoroutineScope by scope {
    private val entriesMutex: Mutex = Mutex()
    private val openSessions: MutableMap<Int, FileSystemCodexAgentSession> = mutableMapOf()
    private val mutableEntries = MutableStateFlow(initialEntries)

    override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()

    init {
        coroutineContext[Job]?.invokeOnCompletion { openSessions.clear() }
    }

    override suspend fun list(): List<Int> = entriesMutex.withLock {
        requireActive()
        entries.value
    }

    override suspend fun listEntries(): List<CodexSessionEntry> = entriesMutex.withLock {
        requireActive()
        entries.value.map { entryIndex ->
            fileSystemSessionEntry(entryIndex, Path(directory, entryIndex.toString()), fileSystem)
        }
    }

    override suspend fun create(): Int = entriesMutex.withLock {
        requireActive()
        val entryIndex = smallestMissing(entries.value)
        createEmptyFileSystemAgentSessionNode(Path(directory, entryIndex.toString()), fileSystem)
        mutableEntries.value = (entries.value + entryIndex).sorted()
        entryIndex
    }

    override suspend fun open(entryIndex: Int): CodexAgentSession = entriesMutex.withLock {
        requireActive()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        val agentDirectory = Path(directory, entryIndex.toString())
        require(fileSystem.metadataOrNull(agentDirectory)?.isDirectory == true) {
            "Agent entry does not exist: $entryIndex"
        }
        if (entryIndex !in entries.value) {
            mutableEntries.value = (entries.value + entryIndex).sorted()
        }
        openSessions[entryIndex]?.let { session ->
            if (session.coroutineContext[Job]?.isActive == true) return@withLock session
            openSessions.remove(entryIndex)
        }
        val initialSubagentEntries = childDirectories(
            directory = Path(agentDirectory, SubagentsDirectory),
            fileSystem = fileSystem,
        ).map { (index) -> index }
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
                runtimeBuilder = { state, dependencies, agentPathResolver ->
                    state.buildSubagentRuntime(dependencies, agentPathResolver)
                },
                initialSubagentEntries = initialSubagentEntries,
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
            mutableEntries.value = entries.value - entryIndex
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
        val initialSubagentEntries = childDirectories(
            directory = Path(directory, SubagentsDirectory),
            fileSystem = fileSystem,
        ).map { (index) -> index }
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
            runtimeBuilder = { state, dependencies, agentPathResolver ->
                state.buildMasterAgentRuntime(dependencies, agentPathResolver)
            },
            initialSubagentEntries = initialSubagentEntries,
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
