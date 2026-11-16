@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.inmemory

import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.DefaultKodexSessionRenewalInterval
import io.github.stream29.kodex.agentsession.contract.IndexedAgentStopEvent
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAtOrNull
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin as cancelJobAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Process-local root session repository for tests and transient hosts. */
public actual class InMemoryKodexSessionRepository internal constructor(
    scope: CoroutineScope,
    private val dependencies: KodexAgentDependencies,
    private val sessionTtl: Duration,
    private val origin: TimeMark,
    private val onTurnActiveRenewal: suspend () -> Unit,
    private val turnRenewalInterval: () -> Duration,
) :
    KodexRootSessionRepository,
    CoroutineScope by scope {
    private val entriesMutex: Mutex = Mutex()
    private val sessions: MutableMap<Int, SessionNode> = linkedMapOf()
    private val openRoots: MutableMap<Int, OpenSessionHandle> = linkedMapOf()
    private val mutableEntries = MutableStateFlow<List<Int>>(emptyList())
    private val mutableOpenSessions = MutableStateFlow<Map<Int, KodexAgentSession>>(emptyMap())
    private val mutableStopEvents = MutableSharedFlow<IndexedAgentStopEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    actual override val entries: StateFlow<List<Int>> = mutableEntries.asStateFlow()
    actual override val openSessions: StateFlow<Map<Int, KodexAgentSession>> = mutableOpenSessions.asStateFlow()
    actual override val stopEvents: Flow<IndexedAgentStopEvent> = mutableStopEvents.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    entriesMutex.withLock {
                        val handles = openRoots.values.toList()
                        openRoots.clear()
                        mutableOpenSessions.value = emptyMap()
                        for (handle in handles) {
                            drainHandleJobs(handle, currentCoroutineContext()[Job])
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
                owner = openRoots[entryIndex]?.session,
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
                owner = openRoots[entryIndex]?.session,
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
        openRoots[entryIndex]?.let { handle ->
            if (handle.session.coroutineContext[Job]?.isActive == true) {
                renewLocked(handle)
                return@withLock handle.session
            }
            withContext(NonCancellable) {
                closeHandleLocked(entryIndex, handle)
            }
        }
        val opened = InMemoryKodexAgentSession.open(
            entryIndex = entryIndex,
            node = root,
            parentScope = this@InMemoryKodexSessionRepository,
            dependencies = dependencies,
            stopEventsSink = mutableStopEvents,
        )
        installHandleLocked(entryIndex, opened).session
    }

    actual override suspend fun closeSession(entryIndex: Int): Unit = entriesMutex.withLock {
        val handle = openRoots[entryIndex] ?: return@withLock
        withContext(NonCancellable) {
            closeHandleLocked(entryIndex, handle)
        }
    }

    actual override suspend fun closeAllSessions(): Unit = entriesMutex.withLock {
        if (openRoots.isEmpty()) return@withLock
        withContext(NonCancellable) {
            for ((index, handle) in openRoots.toList()) {
                closeHandleLocked(index, handle)
            }
        }
    }

    actual override suspend fun delete(entryIndex: Int) {
        val handle = entriesMutex.withLock {
            requireOpen()
            require(entryIndex >= 0) { "Session entry index must be non-negative." }
            requireSession(entryIndex)
            sessions.remove(entryIndex)
            mutableEntries.value = entries.value - entryIndex
            openRoots.remove(entryIndex)?.also {
                publishOpenSessionsLocked()
            }
        }
        if (handle != null) {
            withContext(NonCancellable) {
                drainDetachedHandle(handle)
            }
        }
    }

    private fun installHandleLocked(
        entryIndex: Int,
        opened: OpenedInMemorySession,
    ): OpenSessionHandle {
        val initialDeadline = if (sessionTtl.isFinite()) origin.elapsedNow() + sessionTtl else Duration.INFINITE
        val handle = OpenSessionHandle(
            session = opened.session,
            storageScope = opened.storageScope,
            deadline = initialDeadline,
        )
        openRoots[entryIndex] = handle
        publishOpenSessionsLocked()

        handle.renewalJob = launch {
            opened.session.runtime.runningTurn.collectLatest { turn ->
                if (turn != null) {
                    while (true) {
                        val renewed = entriesMutex.withLock {
                            if (openRoots[entryIndex] !== handle ||
                                opened.session.coroutineContext[Job]?.isActive != true ||
                                opened.session.runtime.runningTurn.value !== turn
                            ) {
                                false
                            } else {
                                renewLocked(handle)
                                true
                            }
                        }
                        if (!renewed) return@collectLatest
                        onTurnActiveRenewal()
                        delay(nextRenewalDelay())
                    }
                }
            }
        }

        handle.expiryJob = if (sessionTtl.isFinite()) {
            launch {
                while (true) {
                    val remaining = entriesMutex.withLock {
                        if (openRoots[entryIndex] !== handle) return@launch
                        handle.deadline - origin.elapsedNow()
                    }
                    if (remaining.isPositive()) {
                        delay(remaining)
                    }
                    val expired = entriesMutex.withLock {
                        if (openRoots[entryIndex] !== handle) return@launch
                        if (opened.session.runtime.runningTurn.value != null) {
                            renewLocked(handle)
                            false
                        } else if (handle.deadline > origin.elapsedNow()) {
                            false
                        } else {
                            closeHandleLocked(entryIndex, handle)
                            true
                        }
                    }
                    if (expired) return@launch
                }
            }
        } else {
            null
        }

        handle.completionWatcher = launch {
            opened.session.coroutineContext.job.join()
            entriesMutex.withLock {
                if (openRoots[entryIndex] === handle) {
                    withContext(NonCancellable) {
                        openRoots.remove(entryIndex)
                        publishOpenSessionsLocked()
                        handle.renewalJob?.cancelJobAndJoin()
                        handle.expiryJob?.cancelJobAndJoin()
                        handle.storageScope.cancelAndJoin()
                    }
                }
            }
        }

        return handle
    }

    private fun renewLocked(handle: OpenSessionHandle) {
        if (sessionTtl.isFinite()) {
            handle.deadline = maxOf(handle.deadline, origin.elapsedNow() + sessionTtl)
        }
    }

    private fun nextRenewalDelay(): Duration {
        val candidate = minOf(DefaultKodexSessionRenewalInterval, turnRenewalInterval())
        return if (sessionTtl.isFinite()) {
            minOf(candidate, (sessionTtl / 3).coerceAtLeast(1.milliseconds))
        } else {
            candidate.coerceAtLeast(1.milliseconds)
        }
    }

    private suspend fun closeHandleLocked(entryIndex: Int, handle: OpenSessionHandle) {
        val caller = currentCoroutineContext()[Job]
        if (openRoots[entryIndex] === handle) {
            openRoots.remove(entryIndex)
            publishOpenSessionsLocked()
        }
        drainHandleJobs(handle, caller)
    }

    private suspend fun drainDetachedHandle(handle: OpenSessionHandle) {
        drainHandleJobs(handle, currentCoroutineContext()[Job])
    }

    private suspend fun drainHandleJobs(handle: OpenSessionHandle, caller: Job?) =
        withContext(NonCancellable) {
            if (handle.completionWatcher !== caller) {
                handle.completionWatcher?.cancelJobAndJoin()
            }
            if (handle.renewalJob !== caller) {
                handle.renewalJob?.cancelJobAndJoin()
            }
            if (handle.expiryJob !== caller) {
                handle.expiryJob?.cancelJobAndJoin()
            }
            handle.session.cancelAndJoin()
            handle.storageScope.cancelAndJoin()
        }

    private fun publishOpenSessionsLocked() {
        mutableOpenSessions.value = openRoots
            .filterValues { it.session.coroutineContext[Job]?.isActive == true }
            .mapValues { it.value.session }
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

private class OpenSessionHandle(
    val session: InMemoryKodexAgentSession,
    val storageScope: CoroutineScope,
    var deadline: Duration,
) {
    var renewalJob: Job? = null
    var expiryJob: Job? = null
    var completionWatcher: Job? = null
}

private data class OpenedInMemorySession(
    val session: InMemoryKodexAgentSession,
    val storageScope: CoroutineScope,
)

private class InMemoryKodexAgentSession(
    scope: CoroutineScope,
    override val storage: MutableKodexAgentStorage,
    override val runtime: AgentRuntime,
) : KodexAgentSession, CoroutineScope by scope {
    companion object {
        suspend fun open(
            entryIndex: Int,
            node: SessionNode,
            parentScope: CoroutineScope,
            dependencies: KodexAgentDependencies,
            stopEventsSink: MutableSharedFlow<IndexedAgentStopEvent>,
        ): OpenedInMemorySession {
            val storageScope = CoroutineScope(parentScope.coroutineContext.minusKey(Job) + SupervisorJob())
            val sessionScope = parentScope.supervisorChildScope()
            val storage = SessionAgentStorage(storageScope, node.storage, node.storage.latestIndex())
            return try {
                val runtime = sessionScope.KodexAgentState(
                    client = dependencies.client,
                    storage = storage,
                    contextSettings = dependencies.contextSettings,
                ).buildMasterAgentRuntime(dependencies)
                sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runtime.stopEvents.collect { event ->
                        stopEventsSink.tryEmit(IndexedAgentStopEvent(entryIndex = entryIndex, event = event))
                    }
                }
                OpenedInMemorySession(
                    session = InMemoryKodexAgentSession(
                        scope = sessionScope,
                        storage = storage,
                        runtime = runtime,
                    ),
                    storageScope = storageScope,
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    sessionScope.cancelAndJoin()
                    storageScope.cancelAndJoin()
                }
                throw failure
            }
        }
    }
}

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
    storageScope: CoroutineScope,
    private val delegate: InMemoryKodexAgentStorage,
    initialLatestIndex: Int,
) :
    MutableKodexAgentStorage,
    ObservableKodexAgentStorage,
    CoroutineScope by storageScope,
    AutoCloseable {
    private val nonce = MutableStateFlow(Random.nextLong())
    private val tail = MutableStateFlow(initialLatestIndex)

    override val agentId: Int? get() = delegate.agentId
    override val uri: String = delegate.uri
    override val cacheNonce: StateFlow<Long> = nonce.asStateFlow()
    override val latestIndexFlow: StateFlow<Int> = tail.asStateFlow()

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
        return delegate.commit(commit).also { tail.value = it }
    }

    override suspend fun commit(commit: AgentCommit, timestamp: Instant): Int {
        requireActive()
        return delegate.commit(commit, timestamp).also { tail.value = it }
    }

    override suspend fun commit(commits: List<AgentCommit>): Int {
        requireActive()
        return delegate.commit(commits).also { tail.value = it }
    }

    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        requireActive()
        return delegate.commit(commits, timestamp).also { tail.value = it }
    }

    override suspend fun revert(untilExclusive: Int) {
        requireActive()
        val previous = tail.value
        val hadSteer = previous >= 0 && delegate.getSteerAt(previous).isNotEmpty()
        delegate.revert(untilExclusive)
        val latest = if (previous < untilExclusive) previous else delegate.latestIndex()
        if (latest != previous || hadSteer) {
            tail.value = latest
            var nextNonce = Random.nextLong()
            while (nextNonce == nonce.value) nextNonce = Random.nextLong()
            nonce.value = nextNonce
        }
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
        target.commit(
            commits.map { commit ->
                if (commit is SteerSnapshot) SteerSnapshot(emptyList()) else commit
            },
        )
    }
}
