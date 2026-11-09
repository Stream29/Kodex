@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.inmemory

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAtOrNull
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

/** Process-local root session repository for tests and transient hosts. */
public actual class InMemoryKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val dependencies: KodexAgentDependencies,
) :
    KodexRootSessionRepository,
    CoroutineScope by scope {
    private val entriesMutex: Mutex = Mutex()
    private val sessions: MutableMap<Int, SessionNode> = linkedMapOf()
    private val openRoots: MutableMap<Int, InMemoryKodexAgentSession> = mutableMapOf()
    private val mutableEntries = MutableStateFlow<List<Int>>(emptyList())

    actual override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()

    init {
        coroutineContext[Job]?.invokeOnCompletion { openRoots.clear() }
    }

    actual override suspend fun list(): List<Int> = entriesMutex.withLock {
        requireOpen()
        entries.value
    }

    actual override suspend fun listEntries(): List<KodexRootSessionEntry> =
        listEntries(includeArchived = true)

    actual override suspend fun listEntries(
        includeArchived: Boolean,
    ): List<KodexRootSessionEntry> = entriesMutex.withLock {
        requireOpen()
        entries.value.mapNotNull { entryIndex ->
            val session = requireSession(entryIndex)
            if (session.archived && !includeArchived) return@mapNotNull null
            session.rootEntry(
                entryIndex = entryIndex,
                owner = openRoots[entryIndex],
                updateArchived = { updated ->
                    updateArchived(entryIndex, updated)
                },
            )
        }
    }

    actual override suspend fun getEntry(entryIndex: Int): KodexRootSessionEntry =
        entriesMutex.withLock {
            requireOpen()
            requireSession(entryIndex).rootEntry(
                entryIndex = entryIndex,
                owner = openRoots[entryIndex],
                updateArchived = { updated ->
                    updateArchived(entryIndex, updated)
                },
            )
        }

    private suspend fun updateArchived(
        entryIndex: Int,
        archived: Boolean,
    ): Unit = entriesMutex.withLock {
        requireOpen()
        requireSession(entryIndex).archived = archived
    }

    actual override suspend fun create(): Int = entriesMutex.withLock {
        requireOpen()
        val index = nextSessionIndex()
        check(sessions.put(index, SessionNode(InMemoryKodexAgentStorage.empty())) == null)
        mutableEntries.value = (entries.value + index).sorted()
        index
    }

    actual override suspend fun createFork(sourceEntryIndex: Int): Int = entriesMutex.withLock {
        requireOpen()
        val source = requireSession(sourceEntryIndex).storage
        val index = nextSessionIndex()
        val storage = InMemoryKodexAgentStorage.empty()
        cloneStorage(source, storage)
        sessions[index] = SessionNode(storage)
        mutableEntries.value = (entries.value + index).sorted()
        index
    }

    actual override suspend fun open(entryIndex: Int): KodexAgentSession = entriesMutex.withLock {
        requireOpen()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        val root = requireSession(entryIndex)
        openRoots[entryIndex]?.let { session ->
            if (session.coroutineContext[Job]?.isActive == true) return@withLock session
            openRoots.remove(entryIndex)
        }
        InMemoryKodexAgentSession.open(
            node = root,
            parentScope = this@InMemoryKodexSessionRepository,
            dependencies = dependencies,
        ).also { session ->
            openRoots[entryIndex] = session
        }
    }

    actual override suspend fun delete(entryIndex: Int) {
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
        while (value in sessions) value += 1
        return value
    }

    private fun requireSession(index: Int): SessionNode =
        requireNotNull(sessions[index]) { "No session exists at index $index." }

    private fun requireOpen() {
        check(coroutineContext[Job]?.isActive == true) { "Session repository is closed." }
    }
}

private class InMemoryKodexAgentSession(
    scope: CoroutineScope,
    override val storage: MutableKodexAgentStorage,
    override val runtime: AgentRuntime,
) : KodexAgentSession, CoroutineScope by scope {
    companion object {
        suspend fun open(
            node: SessionNode,
            parentScope: CoroutineScope,
            dependencies: KodexAgentDependencies,
        ): InMemoryKodexAgentSession {
            val scope = parentScope.supervisorChildScope()
            val storage = SessionAgentStorage(scope, node.storage)
            return try {
                InMemoryKodexAgentSession(
                    scope = scope,
                    storage = storage,
                    runtime = scope.KodexAgentState(
                        client = dependencies.client,
                        storage = storage,
                        contextSettings = dependencies.contextSettings,
                        mcpService = dependencies.mcpService,
                    ).buildMasterAgentRuntime(dependencies),
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) { scope.cancelAndJoin() }
                throw failure
            }
        }
    }
}

/** Creates an in-memory session repository owned by this scope. */
public actual fun CoroutineScope.InMemoryKodexSessionRepository(
    dependencies: KodexAgentDependencies,
): InMemoryKodexSessionRepository =
    InMemoryKodexSessionRepository(
        scope = supervisorChildScope(),
        dependencies = dependencies,
    )

private class SessionNode(
    val storage: InMemoryKodexAgentStorage,
) {
    var archived: Boolean = false
}

private data class InMemorySessionEntry(
    override val entryIndex: Int,
    override val threadName: String?,
    override val createdAt: Instant?,
    override val lastActivityAt: Instant?,
) : KodexSessionEntry

private class InMemoryRootSessionEntry(
    private val delegate: KodexSessionEntry,
    override val archived: Boolean,
    override val isActive: Boolean,
    override val running: Boolean,
    private val updateArchived: suspend (Boolean) -> Unit,
) :
    KodexRootSessionEntry,
    KodexSessionEntry by delegate {
    override suspend fun archive(): Unit = updateArchived(true)

    override suspend fun unarchive(): Unit = updateArchived(false)
}

private suspend fun SessionNode.entry(entryIndex: Int): KodexSessionEntry {
    val latest = storage.latestIndex()
    return InMemorySessionEntry(
        entryIndex = entryIndex,
        threadName = if (latest >= 0) storage.getSettingsAtOrNull(latest)?.threadName else null,
        createdAt = if (latest >= 0) storage.getCommitExact(0)?.createdAt?.takeIf { it != Instant.DISTANT_PAST } else null,
        lastActivityAt = if (latest >= 0) storage.getCommitAtOrBefore(latest)?.createdAt?.takeIf { it != Instant.DISTANT_PAST } else null,
    )
}

private suspend fun SessionNode.rootEntry(
    entryIndex: Int,
    owner: KodexAgentSession?,
    updateArchived: suspend (Boolean) -> Unit,
): KodexRootSessionEntry {
    val entry = entry(entryIndex)
    val activeOwner = owner?.takeIf { it.coroutineContext[Job]?.isActive == true }
    return InMemoryRootSessionEntry(
        delegate = entry,
        archived = archived,
        isActive = activeOwner != null,
        running = activeOwner?.runtime?.runningTurn?.value != null,
        updateArchived = updateArchived,
    )
}

private class SessionAgentStorage(
    parentScope: CoroutineScope,
    private val delegate: InMemoryKodexAgentStorage,
) :
    MutableKodexAgentStorage,
    CoroutineScope by parentScope.supervisorChildScope(),
    AutoCloseable {
    override val uri: String = delegate.uri

    override suspend fun latestIndex(): Int {
        requireActive()
        return delegate.latestIndex()
    }

    override suspend fun countCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): Int {
        requireActive()
        return delegate.countCommitsIn(range, types)
    }

    override suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        requireActive()
        return delegate.getFirstCommitsIn(range, types, limit)
    }

    override suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        requireActive()
        return delegate.getLastCommitsIn(range, types, limit)
    }

    override suspend fun commit(commit: AgentCommit): Int {
        requireActive()
        return delegate.commit(commit)
    }

    override suspend fun commit(commit: AgentCommit, timestamp: Instant): Int {
        requireActive()
        return delegate.commit(commit, timestamp)
    }

    override suspend fun commit(commits: List<AgentCommit>): Int {
        requireActive()
        return delegate.commit(commits)
    }

    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        requireActive()
        return delegate.commit(commits, timestamp)
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

private suspend fun cloneStorage(
    source: KodexAgentStorage,
    target: MutableKodexAgentStorage,
) {
    val latest = source.latestIndex()
    if (latest < 0) return
    val grouped = source.getCommitsIn(0..latest).groupBy({ it.index }, { it.commit })
    for (index in 0..latest) {
        val commits = grouped[index] ?: continue
        target.commit(commits)
    }
}
