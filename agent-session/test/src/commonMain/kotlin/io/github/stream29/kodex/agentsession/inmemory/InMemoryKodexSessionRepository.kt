@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.inmemory

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.IndexedAgentStopEvent
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.ScopedObservableAgentStorage
import io.github.stream29.kodex.agentsession.contract.SessionResidencyTable
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAtOrNull
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Process-local root session repository for tests and transient hosts. */
public actual class InMemoryKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val dependencies: KodexAgentDependencies,
    sessionTtl: Duration,
    origin: TimeMark,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
) :
    KodexSessionRepository,
    CoroutineScope by scope {
    private val entriesMutex: Mutex = Mutex()
    private val sessions: MutableMap<Int, SessionNode> = linkedMapOf()
    private val residency = SessionResidencyTable(
        scope = this,
        mutex = entriesMutex,
        sessionTtl = sessionTtl,
        origin = origin,
        onTurnActiveRenewal = onTurnActiveRenewal,
        turnRenewalInterval = turnRenewalInterval,
    )
    private val mutableEntries = MutableStateFlow<List<Int>>(emptyList())
    private val mutableStopEvents = MutableSharedFlow<IndexedAgentStopEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    actual override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()
    actual override val openSessions: StateFlow<Map<Int, KodexAgentSession>> = residency.openSessions
    actual override val stopEvents: Flow<IndexedAgentStopEvent> = mutableStopEvents.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    entriesMutex.withLock {
                        val handles = residency.detachAllLocked()
                        for (handle in handles) {
                            residency.drainDetached(handle)
                        }
                    }
                }
            }
        }
    }

    actual override suspend fun list(): List<Int> = entriesMutex.withLock {
        requireOpen()
        entries.value
    }

    actual override suspend fun listEntries(
        includeArchived: Boolean,
    ): List<KodexSessionEntry> = entriesMutex.withLock {
        requireOpen()
        entries.value.mapNotNull { entryIndex ->
            val session = requireSession(entryIndex)
            if (session.archived && !includeArchived) return@mapNotNull null
            session.toEntry(
                entryIndex = entryIndex,
                owner = residency.activeSessionOrNullLocked(entryIndex),
                updateArchived = { updated -> updateArchived(entryIndex, updated) },
            )
        }
    }

    actual override suspend fun getEntry(entryIndex: Int): KodexSessionEntry =
        entriesMutex.withLock {
            requireOpen()
            requireSession(entryIndex).toEntry(
                entryIndex = entryIndex,
                owner = residency.activeSessionOrNullLocked(entryIndex),
                updateArchived = { updated -> updateArchived(entryIndex, updated) },
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
        check(sessions.put(index, SessionNode(InMemoryKodexAgentStorage.empty(index))) == null)
        mutableEntries.value = (entries.value + index).sorted()
        index
    }

    actual override suspend fun createFork(sourceEntryIndex: Int): Int = entriesMutex.withLock {
        requireOpen()
        val source = requireSession(sourceEntryIndex).storage
        val index = nextSessionIndex()
        val storage = InMemoryKodexAgentStorage.empty(index)
        cloneStorage(source, storage)
        sessions[index] = SessionNode(storage)
        mutableEntries.value = (entries.value + index).sorted()
        index
    }

    actual override suspend fun open(entryIndex: Int): KodexAgentSession = entriesMutex.withLock {
        requireOpen()
        require(entryIndex >= 0) { "Session entry index must be non-negative." }
        val root = requireSession(entryIndex)
        residency.reuseOrCleanupLocked(entryIndex)?.let { return@withLock it }
        val storageScope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
        val sessionScope = supervisorChildScope()
        val storage = ScopedObservableAgentStorage(
            owner = storageScope,
            source = root.storage,
            initialLatestIndex = root.storage.latestIndex(),
            closedMessage = "AgentSession timeline is closed.",
        )
        try {
            val runtime = sessionScope.KodexAgentState(
                client = dependencies.client,
                storage = storage,
                contextSettings = dependencies.contextSettings,
            ).buildMasterAgentRuntime(dependencies)
            sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.stopEvents.collect { event ->
                    mutableStopEvents.tryEmit(IndexedAgentStopEvent(entryIndex = entryIndex, event = event))
                }
            }
            val session = InMemoryKodexAgentSession(
                scope = sessionScope,
                storage = storage,
                runtime = runtime,
            )
            residency.installLocked(entryIndex, session, storageScope)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                sessionScope.cancelAndJoin()
                storageScope.cancelAndJoin()
            }
            throw failure
        }
    }

    actual override suspend fun closeSession(entryIndex: Int): Unit = entriesMutex.withLock {
        residency.closeLocked(entryIndex)
    }

    actual override suspend fun closeAllSessions(): Unit = entriesMutex.withLock {
        residency.closeAllLocked()
    }

    actual override suspend fun delete(entryIndex: Int) {
        val handle = entriesMutex.withLock {
            requireOpen()
            require(entryIndex >= 0) { "Session entry index must be non-negative." }
            requireSession(entryIndex)
            sessions.remove(entryIndex)
            mutableEntries.value = entries.value - entryIndex
            residency.detachLocked(entryIndex)
        }
        if (handle != null) {
            withContext(NonCancellable) {
                residency.drainDetached(handle)
            }
        }
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
    override val storage: MutableObservableKodexAgentStorage,
    override val runtime: AgentRuntime,
) : KodexAgentSession, CoroutineScope by scope

/** Creates an in-memory session repository owned by this scope. */
public actual fun CoroutineScope.InMemoryKodexSessionRepository(
    dependencies: KodexAgentDependencies,
    sessionTtl: Duration,
    timeSource: TimeSource,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
): InMemoryKodexSessionRepository {
    require(sessionTtl.isPositive()) { "sessionTtl must be positive: $sessionTtl" }
    return InMemoryKodexSessionRepository(
        scope = supervisorChildScope(),
        dependencies = dependencies,
        sessionTtl = sessionTtl,
        origin = timeSource.markNow(),
        onTurnActiveRenewal = onTurnActiveRenewal,
        turnRenewalInterval = turnRenewalInterval,
    )
}

private class SessionNode(
    val storage: InMemoryKodexAgentStorage,
) {
    var archived: Boolean = false
}

private suspend fun SessionNode.toEntry(
    entryIndex: Int,
    owner: KodexAgentSession?,
    updateArchived: suspend (Boolean) -> Unit,
): KodexSessionEntry {
    val latest = storage.latestIndex()
    val threadName = if (latest >= 0) storage.getSettingsAtOrNull(latest)?.threadName else null
    val createdAt = if (latest >= 0) storage.getCommitExact(0)?.createdAt?.takeIf { it != Instant.DISTANT_PAST } else null
    val lastActivityAt = if (latest >= 0) storage.getCommitAtOrBefore(latest)?.createdAt?.takeIf { it != Instant.DISTANT_PAST } else null
    return object : KodexSessionEntry {
        override val entryIndex: Int = entryIndex
        override val threadName: String? = threadName
        override val createdAt: Instant? = createdAt
        override val lastActivityAt: Instant? = lastActivityAt
        override val archived: Boolean = this@toEntry.archived
        override val isActive: Boolean = owner != null
        override val running: Boolean = owner?.runtime?.runningTurn?.value != null
        override suspend fun archive(): Unit = updateArchived(true)
        override suspend fun unarchive(): Unit = updateArchived(false)
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
        val createdAt = source.getCommitExact(index)?.createdAt ?: Instant.DISTANT_PAST
        val mapped = commits.map { commit ->
            if (commit is SteerSnapshot) SteerSnapshot(emptyList()) else commit
        }
        if (createdAt != Instant.DISTANT_PAST) {
            target.commit(mapped, createdAt)
        } else {
            target.commit(mapped)
        }
    }
}
