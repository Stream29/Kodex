@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin as cancelJobAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark

public actual class ScopedObservableAgentStorage actual constructor(
    private val owner: CoroutineScope,
    private val source: MutableKodexAgentStorage,
    initialLatestIndex: Int,
    private val closedMessage: String,
) : MutableObservableKodexAgentStorage {
    private val nonce = MutableStateFlow(Random.nextLong())
    private val tail = MutableStateFlow(initialLatestIndex)

    public actual override val agentId: Int? get() = source.agentId
    public actual override val uri: String get() = source.uri
    public actual override val cacheNonce: StateFlow<Long> = nonce.asStateFlow()
    public actual override val latestIndexFlow: StateFlow<Int> = tail.asStateFlow()

    public actual fun checkOpen() {
        check(owner.isActive) { closedMessage }
    }

    public actual fun publishCommittedIndex(index: Int) {
        tail.value = index
    }

    private fun backing(): MutableKodexAgentStorage {
        checkOpen()
        return source
    }

    public actual override suspend fun latestIndex(): Int = backing().latestIndex()

    public actual override suspend fun countCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): Int = backing().countCommitsIn(range, types)

    public actual override suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> = backing().getFirstCommitsIn(range, types, limit)

    public actual override suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> = backing().getLastCommitsIn(range, types, limit)

    public actual override suspend fun commit(commit: AgentCommit): Int =
        commit(listOf(commit), Clock.System.now())

    public actual override suspend fun commit(commit: AgentCommit, timestamp: Instant): Int =
        commit(listOf(commit), timestamp)

    public actual override suspend fun commit(commits: List<AgentCommit>): Int =
        commit(commits, Clock.System.now())

    public actual override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        currentCoroutineContext().ensureActive()
        val target = backing()
        return withContext(NonCancellable) {
            val index = target.commit(commits, timestamp)
            tail.value = index
            index
        }
    }

    public actual override suspend fun revert(untilExclusive: Int) {
        currentCoroutineContext().ensureActive()
        val target = backing()
        withContext(NonCancellable) {
            val previous = tail.value
            val hadSteer = previous >= 0 && target.getSteerAt(previous).isNotEmpty()
            target.revert(untilExclusive)
            val latest = if (previous < untilExclusive) previous else target.latestIndex()
            if (latest != previous || hadSteer) {
                tail.value = latest
                var nextNonce = Random.nextLong()
                while (nextNonce == nonce.value) nextNonce = Random.nextLong()
                nonce.value = nextNonce
            }
        }
    }
}

public actual class DetachedSessionHandle internal constructor(
    internal val session: KodexAgentSession,
    internal val storageScope: CoroutineScope,
    internal var deadline: Duration,
) {
    internal var renewalJob: Job? = null
    internal var expiryJob: Job? = null
    internal var completionWatcher: Job? = null
}

public actual class SessionResidencyTable actual constructor(
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val sessionTtl: Duration,
    private val origin: TimeMark,
    private val onTurnActiveRenewal: suspend () -> Unit,
    private val turnRenewalInterval: () -> Duration,
) {
    private val openRoots = linkedMapOf<Int, DetachedSessionHandle>()
    private val mutableOpenSessions = MutableStateFlow<Map<Int, KodexAgentSession>>(emptyMap())

    public actual val openSessions: StateFlow<Map<Int, KodexAgentSession>> = mutableOpenSessions.asStateFlow()

    public actual suspend fun reuseOrCleanupLocked(entryIndex: Int): KodexAgentSession? {
        val existing = openRoots[entryIndex] ?: return null
        if (existing.session.coroutineContext[Job]?.isActive == true) {
            renewLocked(existing)
            return existing.session
        }
        withContext(NonCancellable) {
            closeHandleLocked(entryIndex, existing)
        }
        return null
    }

    public actual fun activeSessionOrNullLocked(entryIndex: Int): KodexAgentSession? =
        openRoots[entryIndex]?.session?.takeIf { it.coroutineContext[Job]?.isActive == true }

    public actual fun installLocked(
        entryIndex: Int,
        session: KodexAgentSession,
        storageScope: CoroutineScope,
    ): KodexAgentSession {
        val initialDeadline = if (sessionTtl.isFinite()) origin.elapsedNow() + sessionTtl else Duration.INFINITE
        val handle = DetachedSessionHandle(
            session = session,
            storageScope = storageScope,
            deadline = initialDeadline,
        )
        openRoots[entryIndex] = handle
        publishOpenSessionsLocked()

        handle.renewalJob = scope.launch {
            session.runtime.runningTurn.collectLatest { turn ->
                if (turn != null) {
                    while (true) {
                        val renewed = mutex.withLock {
                            if (openRoots[entryIndex] !== handle ||
                                session.coroutineContext[Job]?.isActive != true ||
                                session.runtime.runningTurn.value !== turn
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
            scope.launch {
                while (true) {
                    val remaining = mutex.withLock {
                        if (openRoots[entryIndex] !== handle) return@launch
                        handle.deadline - origin.elapsedNow()
                    }
                    if (remaining.isPositive()) {
                        delay(remaining)
                    }
                    val expired = mutex.withLock {
                        if (openRoots[entryIndex] !== handle) return@launch
                        if (session.runtime.runningTurn.value != null) {
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

        handle.completionWatcher = scope.launch {
            session.coroutineContext.job.join()
            mutex.withLock {
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

        return session
    }

    public actual suspend fun closeLocked(entryIndex: Int) {
        val handle = openRoots[entryIndex] ?: return
        withContext(NonCancellable) {
            closeHandleLocked(entryIndex, handle)
        }
    }

    public actual suspend fun closeAllLocked() {
        if (openRoots.isEmpty()) return
        withContext(NonCancellable) {
            for ((index, handle) in openRoots.toList()) {
                closeHandleLocked(index, handle)
            }
        }
    }

    public actual fun detachLocked(entryIndex: Int): DetachedSessionHandle? =
        openRoots.remove(entryIndex)?.also { publishOpenSessionsLocked() }

    public actual fun detachAllLocked(): List<DetachedSessionHandle> {
        val handles = openRoots.values.toList()
        openRoots.clear()
        mutableOpenSessions.value = emptyMap()
        return handles
    }

    public actual suspend fun drainDetached(handle: DetachedSessionHandle) {
        drainHandleJobs(handle, currentCoroutineContext()[Job])
    }

    private fun renewLocked(handle: DetachedSessionHandle) {
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

    private suspend fun closeHandleLocked(entryIndex: Int, handle: DetachedSessionHandle) {
        val caller = currentCoroutineContext()[Job]
        if (openRoots[entryIndex] === handle) {
            openRoots.remove(entryIndex)
            publishOpenSessionsLocked()
        }
        drainHandleJobs(handle, caller)
    }

    private suspend fun drainHandleJobs(handle: DetachedSessionHandle, caller: Job?) =
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
}
