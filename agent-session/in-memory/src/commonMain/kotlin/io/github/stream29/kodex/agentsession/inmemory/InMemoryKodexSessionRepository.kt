package io.github.stream29.kodex.agentsession.inmemory

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildSubagentRuntime
import io.github.stream29.kodex.agentsession.contract.AgentPathResolver
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.multiagent.AgentPathResolverImpl
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as KodexAgentStateContract
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

private typealias AgentRuntimeBuilder = (
    KodexAgentStateContract,
    KodexAgentDependencies,
    AgentPathResolver,
    String,
) -> AgentRuntime

/** Process-local recursive session repository for tests and transient hosts. */
public class InMemoryKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val dependencies: KodexAgentDependencies,
) :
    KodexRootSessionRepository,
    CoroutineScope by scope {
    private val entriesMutex: Mutex = Mutex()
    private val sessions: MutableMap<Int, SessionNode> = linkedMapOf()
    private val openRoots: MutableMap<Int, InMemoryKodexAgentSession> = mutableMapOf()
    private val mutableEntries = MutableStateFlow<List<Int>>(emptyList())

    override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()

    init {
        coroutineContext[Job]?.invokeOnCompletion { openRoots.clear() }
    }

    override suspend fun list(): List<Int> = entriesMutex.withLock {
        requireOpen()
        entries.value
    }

    override suspend fun listEntries(): List<KodexRootSessionEntry> =
        listEntries(includeArchived = true)

    override suspend fun listEntries(
        includeArchived: Boolean,
    ): List<KodexRootSessionEntry> = entriesMutex.withLock {
        requireOpen()
        entries.value.mapNotNull { entryIndex ->
            val session = requireSession(entryIndex)
            if (session.archived && !includeArchived) return@mapNotNull null
            session.rootEntry(
                entryIndex = entryIndex,
                updateArchived = { updated ->
                    updateArchived(entryIndex, updated)
                },
            )
        }
    }

    private suspend fun updateArchived(
        entryIndex: Int,
        archived: Boolean,
    ): Unit = entriesMutex.withLock {
        requireOpen()
        requireSession(entryIndex).archived = archived
    }

    override suspend fun create(): Int = entriesMutex.withLock {
        requireOpen()
        val index = nextSessionIndex()
        check(sessions.put(
            index,
            SessionNode(InMemoryKodexAgentStorage.empty()),
        ) == null)
        mutableEntries.value = (entries.value + index).sorted()
        index
    }

    override suspend fun open(entryIndex: Int): KodexAgentSession = entriesMutex.withLock {
        requireOpen()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        val root = requireSession(entryIndex)
        openRoots[entryIndex]?.let { session ->
            if (session.coroutineContext[Job]?.isActive == true) return@withLock session
            openRoots.remove(entryIndex)
        }
        InMemoryKodexAgentSession.openRoot(
            node = root,
            parentScope = this@InMemoryKodexSessionRepository,
            dependencies = dependencies,
        ).also { session ->
            openRoots[entryIndex] = session
        }
    }

    override suspend fun delete(entryIndex: Int) {
        val openRoot = entriesMutex.withLock {
            requireOpen()
            require(entryIndex >= 0) { "Session entry index must be non-negative." }
            requireSession(entryIndex)
            sessions.remove(entryIndex)
            mutableEntries.value = entries.value - entryIndex
            openRoots.remove(entryIndex)
        }
        withContext(NonCancellable) { openRoot?.cancelAndJoin() }
    }

    private fun nextSessionIndex(): Int {
        var value = 0
        while (value in sessions) {
            value += 1
        }
        return value
    }

    private fun requireSession(index: Int): SessionNode =
        requireNotNull(sessions[index]) { "No session exists at index $index." }

    private fun requireOpen() {
        check(coroutineContext[Job]?.isActive == true) { "Session repository is closed." }
    }

    private class InMemoryKodexAgentSession(
        node: SessionNode,
        scope: CoroutineScope,
        dependencies: KodexAgentDependencies,
        sessionId: String,
        override val storage: MutableKodexAgentStorage,
        state: KodexAgentStateContract,
        createAgentPathResolver: (KodexAgentSession) -> AgentPathResolver,
        runtimeBuilder: AgentRuntimeBuilder,
    ) : KodexAgentSession, CoroutineScope by scope {
        private val agentPathResolver: AgentPathResolver =
            createAgentPathResolver(this)

        override val subagents: KodexSessionRepository = InMemorySubagentRepository(
            children = node.children,
            scope = scope.supervisorChildScope(),
            dependencies = dependencies,
            sessionId = sessionId,
            agentPathResolver = agentPathResolver,
        )

        override val runtime: AgentRuntime =
            runtimeBuilder(state, dependencies, agentPathResolver, sessionId)

        companion object {
            suspend fun openRoot(
                node: SessionNode,
                parentScope: CoroutineScope,
                dependencies: KodexAgentDependencies,
            ): InMemoryKodexAgentSession {
                val scope = parentScope.supervisorChildScope()
                val storage = SessionAgentStorage(scope, node.storage)
                return try {
                    InMemoryKodexAgentSession(
                        node = node,
                        scope = scope,
                        dependencies = dependencies,
                        sessionId = storage.id,
                        storage = storage,
                        state = scope.KodexAgentState(
                            client = dependencies.client,
                            storage = storage,
                            contextSettings = dependencies.contextSettings,
                            mcpService = dependencies.mcpService,
                        ),
                        createAgentPathResolver = { rootSession ->
                            AgentPathResolverImpl(rootSession)
                        },
                        runtimeBuilder = { state, dependencies, agentPathResolver, _ ->
                            state.buildMasterAgentRuntime(dependencies, agentPathResolver)
                        },
                    )
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { scope.cancelAndJoin() }
                    throw failure
                }
            }

            suspend fun openSubagent(
                node: SessionNode,
                parentScope: CoroutineScope,
                dependencies: KodexAgentDependencies,
                sessionId: String,
                agentPathResolver: AgentPathResolver,
            ): InMemoryKodexAgentSession {
                val scope = parentScope.supervisorChildScope()
                val storage = SessionAgentStorage(scope, node.storage)
                return try {
                    InMemoryKodexAgentSession(
                        node = node,
                        scope = scope,
                        dependencies = dependencies,
                        sessionId = sessionId,
                        storage = storage,
                        state = scope.KodexAgentState(
                            client = dependencies.client,
                            storage = storage,
                            contextSettings = dependencies.contextSettings,
                            mcpService = dependencies.mcpService,
                        ),
                        createAgentPathResolver = { agentPathResolver },
                        runtimeBuilder = { state, dependencies, agentPathResolver, sessionId ->
                            state.buildSubagentRuntime(dependencies, agentPathResolver, sessionId)
                        },
                    )
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { scope.cancelAndJoin() }
                    throw failure
                }
            }
        }
    }

    private class InMemorySubagentRepository(
        private val children: MutableMap<Int, SessionNode>,
        scope: CoroutineScope,
        private val dependencies: KodexAgentDependencies,
        private val sessionId: String,
        private val agentPathResolver: AgentPathResolver,
    ) : KodexSessionRepository, CoroutineScope by scope {
        private val entriesMutex: Mutex = Mutex()
        private val openSessions: MutableMap<Int, InMemoryKodexAgentSession> = mutableMapOf()
        private val mutableEntries = MutableStateFlow(children.keys.sorted())

        override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()

        init {
            coroutineContext[Job]?.invokeOnCompletion { openSessions.clear() }
        }

        override suspend fun list(): List<Int> = entriesMutex.withLock {
            requireActive()
            entries.value
        }

        override suspend fun listEntries(): List<KodexSessionEntry> = entriesMutex.withLock {
            requireActive()
            entries.value.map { entryIndex ->
                requireNotNull(children[entryIndex]) {
                    "No Agent entry exists at index $entryIndex."
                }.entry(entryIndex)
            }
        }

        override suspend fun create(): Int = entriesMutex.withLock {
            requireActive()
            val entryIndex = smallestMissing(entries.value)
            children[entryIndex] = SessionNode(InMemoryKodexAgentStorage.empty())
            mutableEntries.value = (entries.value + entryIndex).sorted()
            entryIndex
        }

        override suspend fun open(entryIndex: Int): KodexAgentSession = entriesMutex.withLock {
            requireActive()
            require(entryIndex >= 0) { "Session entry index must be non-negative." }
            val node = requireNotNull(children[entryIndex]) {
                "No Agent entry exists at index $entryIndex."
            }
            openSessions[entryIndex]?.let { session ->
                if (session.coroutineContext[Job]?.isActive == true) return@withLock session
                openSessions.remove(entryIndex)
            }
            InMemoryKodexAgentSession.openSubagent(
                node = node,
                parentScope = this@InMemorySubagentRepository,
                dependencies = dependencies,
                sessionId = sessionId,
                agentPathResolver = agentPathResolver,
            ).also { session ->
                openSessions[entryIndex] = session
            }
        }

        override suspend fun delete(entryIndex: Int) {
            val openSession = entriesMutex.withLock {
                requireActive()
                require(entryIndex >= 0) { "Session entry index must be non-negative." }
                requireNotNull(children.remove(entryIndex)) {
                    "No Agent entry exists at index $entryIndex."
                }
                mutableEntries.value = entries.value - entryIndex
                openSessions.remove(entryIndex)
            }
            withContext(NonCancellable) { openSession?.cancelAndJoin() }
        }

        private fun requireActive() {
            check(coroutineContext[Job]?.isActive == true) { "Subagent repository is closed." }
        }
    }
}

/** Creates an in-memory session repository owned by this scope. */
public fun CoroutineScope.InMemoryKodexSessionRepository(
    dependencies: KodexAgentDependencies,
): InMemoryKodexSessionRepository {
    return InMemoryKodexSessionRepository(
        scope = supervisorChildScope(),
        dependencies = dependencies,
    )
}

private class SessionNode(
    val storage: InMemoryKodexAgentStorage,
) {
    var archived: Boolean = false
    val children: MutableMap<Int, SessionNode> = linkedMapOf()
}

private data class InMemorySessionEntry(
    override val entryIndex: Int,
    override val threadName: String?,
    override val lastActivityAt: Instant?,
) : KodexSessionEntry

private class InMemoryRootSessionEntry(
    private val delegate: KodexSessionEntry,
    override val archived: Boolean,
    private val updateArchived: suspend (Boolean) -> Unit,
) :
    KodexRootSessionEntry,
    KodexSessionEntry by delegate {
    override suspend fun archive(): Unit = updateArchived(true)

    override suspend fun unarchive(): Unit = updateArchived(false)
}

private suspend fun SessionNode.entry(entryIndex: Int): KodexSessionEntry {
    val settingsIndex = storage.settings.latestIndex()
    val timestampIndex = storage.timestamp.latestIndex()
    return InMemorySessionEntry(
        entryIndex = entryIndex,
        threadName = settingsIndex.takeIf { it >= 0 }?.let { index -> storage.settings[index].threadName },
        lastActivityAt = timestampIndex.takeIf { it >= 0 }?.let { index -> storage.timestamp[index] },
    )
}

private suspend fun SessionNode.rootEntry(
    entryIndex: Int,
    updateArchived: suspend (Boolean) -> Unit,
): KodexRootSessionEntry {
    val entry = entry(entryIndex)
    return InMemoryRootSessionEntry(
        delegate = entry,
        archived = archived,
        updateArchived = updateArchived,
    )
}

private fun smallestMissing(values: Iterable<Int>): Int {
    var candidate = 0
    while (candidate in values) candidate += 1
    return candidate
}

private class SessionAgentStorage(
    parentScope: CoroutineScope,
    delegate: InMemoryKodexAgentStorage,
) : MutableKodexAgentStorage {
    override val id: String = delegate.id
    override val compaction = SessionIndexVersioned(parentScope, delegate.compaction)
    override val settings = SessionIndexVersioned(parentScope, delegate.settings)
    override val timestamp = SessionIndexVersioned(parentScope, delegate.timestamp)
    override val tokenCount = SessionIndexVersioned(parentScope, delegate.tokenCount)
    override val stable = SessionIndexVersioned(parentScope, delegate.stable)
    override val unstable = SessionIndexVersioned(parentScope, delegate.unstable)
}

private class SessionIndexVersioned<T>(
    parentScope: CoroutineScope,
    private val delegate: MutableIndexVersioned<T>,
) :
    MutableIndexVersioned<T>,
    CoroutineScope by parentScope.supervisorChildScope(),
    AutoCloseable {

    override suspend fun latestIndex(): Int {
        requireActive()
        return delegate.latestIndex()
    }

    override suspend fun get(index: Int): T {
        requireActive()
        return delegate[index]
    }

    override suspend fun floorToIndex(index: Int): Int? {
        requireActive()
        return delegate.floorToIndex(index)
    }

    override suspend fun ceilToIndex(index: Int): Int? {
        requireActive()
        return delegate.ceilToIndex(index)
    }

    override suspend fun set(index: Int, value: T) {
        requireActive()
        delegate[index] = value
    }

    override suspend fun revert(untilExclusive: Int) {
        requireActive()
        delegate.revert(untilExclusive)
    }

    override fun close() {
        coroutineContext.cancel()
    }

    private fun requireActive() {
        check(coroutineContext[Job]?.isActive == true) { "AgentSession timeline is closed." }
    }
}
