package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Instant

val subagentReturnAnchorsTest by testSuite {
    test("creation and completion search separate bounded pages on the supplied parent") {
        val store = ParentHistoryFixture()
        val a = 91
        val b = 92
        store.rows = listOf(created(3, a), completed(5, a)) +
            (6..160).map { index -> if (index % 2 == 0) created(index, b) else completed(index, b) }
        assertEquals(SubagentReturnAnchorResult.Found(3, 41L), store.find(a, AgentCommitType.EventSubagentCreated))
        assertEquals(listOf(0..160, 0..33), store.requests.map { it.first })
        assertTrue(store.requests.all { it.second == setOf(AgentCommitType.EventSubagentCreated) })
        store.requests.clear()
        assertEquals(SubagentReturnAnchorResult.Found(5, 41L), store.find(a, AgentCommitType.EventSubagentCompleted))
        assertEquals(2, store.requests.size)
        assertTrue(store.requests.all { it.second == setOf(AgentCommitType.EventSubagentCompleted) })
        assertEquals(SubagentReturnAnchorResult.Found(159, 41L), store.find(b, AgentCommitType.EventSubagentCompleted))
    }

    test("missing, reused positions, invalidation and failed reads have distinct outcomes") {
        val store = ParentHistoryFixture()
        val type = AgentCommitType.EventSubagentCreated
        assertEquals(SubagentReturnAnchorResult.Missing, store.find(91, type))
        assertTrue(store.requests.isEmpty())
        store.rows = listOf(created(3, 91))
        store.afterRead = {
            store.cacheNonce.value++
            store.rows = listOf(created(3, 92))
        }
        assertEquals(SubagentReturnAnchorResult.Invalidated, store.find(91, type))
        store.afterRead = {}
        assertEquals(SubagentReturnAnchorResult.Missing, store.find(91, type))
        assertEquals(SubagentReturnAnchorResult.Found(3, 42L), store.find(92, type))
        store.afterRead = { store.active = false }
        assertEquals(SubagentReturnAnchorResult.Invalidated, store.find(92, type))
        store.active = true
        store.afterRead = { throw CacheNonceMismatch() }
        assertEquals(SubagentReturnAnchorResult.Invalidated, store.find(92, type))
        val failure = IllegalArgumentException("fixture read failure")
        store.afterRead = { throw failure }
        assertSame(failure, assertIs<SubagentReturnAnchorResult.ReadFailed>(store.find(92, type)).cause)
        store.afterRead = { throw CancellationException("fixture cancellation") }
        assertFailsWith<CancellationException> { store.find(92, type) }
    }
}

private fun created(index: Int, child: Int) =
    IndexedAgentCommit(index, StableSubagentCreatedEvent(child), Instant.DISTANT_PAST)

private fun completed(index: Int, child: Int) =
    IndexedAgentCommit(index, StableSubagentCompletedEvent(child), Instant.DISTANT_PAST)

private class ParentHistoryFixture : ObservableKodexAgentStorage {
    override val uri = "fixture:parent"
    override val cacheNonce = MutableStateFlow(41L)
    override val latestIndexFlow = MutableStateFlow(-1)
    var rows = emptyList<IndexedAgentCommit>()
    var active = true
    var afterRead: () -> Unit = {}
    val requests = mutableListOf<Pair<IntRange, Set<AgentCommitType>>>()
    override suspend fun latestIndex(): Int = rows.lastOrNull()?.index ?: -1
    override suspend fun countCommitsIn(range: IntRange, types: Set<AgentCommitType>): Int = error("Unexpected count")
    override suspend fun getFirstCommitsIn(range: IntRange, types: Set<AgentCommitType>, limit: Int): List<IndexedAgentCommit> =
        error("Expected a descending-page search")

    override suspend fun getLastCommitsIn(
        range: IntRange, types: Set<AgentCommitType>, limit: Int,
    ): List<IndexedAgentCommit> {
        assertEquals(64, limit)
        requests += range to types
        val result = rows.filter { it.index in range && it.commit.commitType in types }.takeLast(limit)
        afterRead()
        return result
    }

    suspend fun find(child: Int, type: AgentCommitType): SubagentReturnAnchorResult =
        findSubagentReturnAnchor(this, child, type) { active }
}
