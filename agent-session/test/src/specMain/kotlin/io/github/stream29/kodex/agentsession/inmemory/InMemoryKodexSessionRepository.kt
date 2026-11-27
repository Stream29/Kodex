@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentsession.inmemory

import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentsession.contract.AgentForkCopy
import io.github.stream29.kodex.agentsession.contract.DefaultKodexSessionRenewalInterval
import io.github.stream29.kodex.agentsession.contract.DefaultKodexSessionTtl
import io.github.stream29.kodex.agentsession.contract.IndexedAgentStopEvent
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionEntry
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.StoredAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Process-local root session repository for tests and transient hosts. */
public expect class InMemoryKodexSessionRepository : KodexSessionRepository {
    override val coroutineContext: CoroutineContext
    override val entries: StateFlow<List<Int>>
    override val openSessions: StateFlow<Map<Int, KodexAgentSession>>
    override val pendingAwaitDeadlines: StateFlow<Map<Int, PendingAwaitDeadline>>
    override val stopEvents: Flow<IndexedAgentStopEvent>
    override suspend fun list(): List<Int>
    override suspend fun listEntries(includeArchived: Boolean): List<KodexSessionEntry>
    override suspend fun getEntry(entryIndex: Int): KodexSessionEntry
    override suspend fun create(): Int
    override suspend fun createFork(sourceEntryIndex: Int): Int
    override suspend fun createAgentFork(sourceAgentId: Int, expectedOwner: KodexAgentSession?, untilExclusive: Int?): AgentForkCopy?
    override suspend fun open(entryIndex: Int): KodexAgentSession
    override suspend fun openAgent(agentId: Int): KodexAgentSession?
    override suspend fun openForAwaitDeadline(agentId: Int, expected: PendingAwaitDeadline): KodexAgentSession?
    override suspend fun getAgentRecord(agentId: Int): StoredAgent?
    override suspend fun closeSession(entryIndex: Int)
    override suspend fun closeAllSessions()
    override suspend fun delete(entryIndex: Int)
}

/** Creates an in-memory session repository owned by this scope. */
public expect fun CoroutineScope.InMemoryKodexSessionRepository(
    dependencies: KodexAgentDependencies,
    sessionTtl: Duration = DefaultKodexSessionTtl,
    timeSource: TimeSource = TimeSource.Monotonic,
    onTurnActiveRenewal: suspend () -> Unit = {},
    turnRenewalInterval: () -> Duration = { DefaultKodexSessionRenewalInterval },
): InMemoryKodexSessionRepository
