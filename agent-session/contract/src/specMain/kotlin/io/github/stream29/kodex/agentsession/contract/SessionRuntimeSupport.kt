@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableObservableKodexAgentStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeMark

/**
 * Observable wrapper over a [MutableKodexAgentStorage] guarded by [owner]'s lifecycle.
 */
public expect class ScopedObservableAgentStorage(
    owner: CoroutineScope,
    source: MutableKodexAgentStorage,
    initialLatestIndex: Int,
    closedMessage: String = "Session storage is closed",
) : MutableObservableKodexAgentStorage {
    override val agentId: Int?
    override val uri: String
    override val cacheNonce: StateFlow<Long>
    override val latestIndexFlow: StateFlow<Int>
    override suspend fun latestIndex(): Int
    override suspend fun countCommitsIn(range: IntRange, types: Set<AgentCommitType>): Int
    override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun getLastCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit>
    override suspend fun commit(commit: AgentCommit): Int
    override suspend fun commit(commit: AgentCommit, timestamp: Instant): Int
    override suspend fun commit(commits: List<AgentCommit>): Int
    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int
    override suspend fun revert(untilExclusive: Int)
    public fun checkOpen()
    public fun publishCommittedIndex(index: Int)
}

/**
 * Coordinates open [KodexAgentSession] handles, active-turn TTL renewal, idle TTL eviction,
 * completion cleanup, and orderly shutdown draining for session repositories.
 */
public expect class SessionResidencyTable(
    scope: CoroutineScope,
    mutex: Mutex,
    sessionTtl: Duration,
    origin: TimeMark,
    onTurnActiveRenewal: suspend () -> Unit,
    turnRenewalInterval: () -> Duration,
) {
    public val openSessions: StateFlow<Map<Int, KodexAgentSession>>

    /** Returns an active session under [mutex] and renews its TTL, or closes stale handles and returns `null`. */
    public suspend fun reuseOrCleanupLocked(entryIndex: Int): KodexAgentSession?

    /** Registers [session] and its [storageScope] under [mutex] and starts renewal/expiry/completion watchers. */
    public fun installLocked(
        entryIndex: Int,
        session: KodexAgentSession,
        storageScope: CoroutineScope,
    ): KodexAgentSession

    public fun activeSessionOrNullLocked(entryIndex: Int): KodexAgentSession?

    public suspend fun closeLocked(entryIndex: Int)

    public suspend fun closeAllLocked()

    /** Removes [entryIndex] from [openSessions] under [mutex], returning a handle to drain via [drainDetached]. */
    public fun detachLocked(entryIndex: Int): DetachedSessionHandle?

    /** Removes all open handles under [mutex], returning them for [drainDetached]. */
    public fun detachAllLocked(): List<DetachedSessionHandle>

    public suspend fun drainDetached(handle: DetachedSessionHandle)
}

public expect class DetachedSessionHandle
