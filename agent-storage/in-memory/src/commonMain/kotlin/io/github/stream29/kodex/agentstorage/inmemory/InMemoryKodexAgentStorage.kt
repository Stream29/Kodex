@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.inmemory

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.validateCommitBatch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private data class InMemoryRow(
    val index: Int,
    val commits: List<AgentCommit>,
    val createdAt: Instant,
)

/**
 * Process-local mutable storage for tests and transient agent sessions.
 */
@OptIn(ExperimentalUuidApi::class)
public actual class InMemoryKodexAgentStorage private constructor(
    initialRows: MutableList<InMemoryRow>,
    public actual override val agentId: Int?,
) : MutableKodexAgentStorage {
    private val identity: Any = Any()
    private val mutex = Mutex()
    private val rowsByIndex: MutableList<InMemoryRow> = initialRows

    public actual constructor(
        initialSettings: KodexAgentSettings,
        agentId: Int?,
    ) : this(
        initialRows(initialSettings),
        agentId,
    )

    public actual override val uri: String = "memory:${identity.hashCode().toUInt().toString(16)}"

    public actual override suspend fun latestIndex(): Int = mutex.withLock { tailIndex() }

    private fun tailIndex(): Int = rowsByIndex.lastOrNull()?.index ?: -1

    private fun rowPositionAtOrAfter(index: Int): Int = rowsByIndex.binarySearchBy(index) { it.index }.let {
        if (it < 0) -it - 1 else it
    }

    public actual override suspend fun countCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): Int {
        if (range.isEmpty() || types.isEmpty()) return 0
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        return mutex.withLock {
            var count = 0
            for (position in rowPositionAtOrAfter(range.first)..rowsByIndex.lastIndex) {
                val row = rowsByIndex[position]
                if (row.index > range.last) break
                for (commit in row.commits) {
                    if (commit.commitType in types) {
                        count++
                    }
                }
            }
            count
        }
    }

    public actual override suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        require(limit >= 0) { "Limit $limit must be non-negative." }
        if (limit == 0 || range.isEmpty() || types.isEmpty()) return emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        return mutex.withLock {
            buildList(minOf(limit, 64)) {
                for (position in rowPositionAtOrAfter(range.first)..rowsByIndex.lastIndex) {
                    val row = rowsByIndex[position]
                    if (row.index > range.last) break
                    for (commit in row.commits) {
                        if (commit.commitType in types) {
                            add(IndexedAgentCommit(row.index, commit, row.createdAt))
                            if (size == limit) return@buildList
                        }
                    }
                }
            }
        }
    }

    public actual override suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> {
        require(limit >= 0) { "Limit $limit must be non-negative." }
        if (limit == 0 || range.isEmpty() || types.isEmpty()) return emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        return mutex.withLock {
            val afterLast = if (range.last == Int.MAX_VALUE) rowsByIndex.size else rowPositionAtOrAfter(range.last + 1)
            val reversed = ArrayList<IndexedAgentCommit>(minOf(limit, 64))
            outer@ for (position in (afterLast - 1) downTo 0) {
                val row = rowsByIndex[position]
                if (row.index < range.first) break
                val commitsAtIdx = row.commits
                for (i in commitsAtIdx.indices.reversed()) {
                    val commit = commitsAtIdx[i]
                    if (commit.commitType in types) {
                        reversed.add(IndexedAgentCommit(row.index, commit, row.createdAt))
                        if (reversed.size == limit) break@outer
                    }
                }
            }
            reversed.asReversed()
        }
    }

    public actual override suspend fun commit(commits: List<AgentCommit>): Int =
        commit(commits, Clock.System.now())

    public actual override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        validateCommitBatch(commits)
        val sorted = commits.sortedBy { it.commitType.ordinal }
        return mutex.withLock {
            check(tailIndex() < Int.MAX_VALUE) { "Agent storage index is exhausted." }
            val nextIndex = tailIndex() + 1
            rowsByIndex.add(InMemoryRow(nextIndex, sorted, timestamp))
            nextIndex
        }
    }

    public actual override suspend fun revert(untilExclusive: Int) {
        require(untilExclusive >= 0) { "Revert boundary $untilExclusive must be non-negative." }
        mutex.withLock {
            rowsByIndex.subList(rowPositionAtOrAfter(untilExclusive), rowsByIndex.size).clear()
            for (idx in rowsByIndex.indices) {
                val row = rowsByIndex[idx]
                if (row.commits.any { it is SteerSnapshot && it.events.isNotEmpty() }) {
                    rowsByIndex[idx] = row.copy(
                        commits = row.commits.map { commit ->
                            if (commit is SteerSnapshot) SteerSnapshot(emptyList()) else commit
                        },
                    )
                }
            }
        }
    }

    public actual companion object {
        /** Creates the uninitialized storage backing a freshly spawned AgentSession. */
        public actual fun empty(agentId: Int?): InMemoryKodexAgentStorage =
            InMemoryKodexAgentStorage(mutableListOf(), agentId)

        public actual fun importCommits(agentId: Int?, commits: List<IndexedAgentCommit>): InMemoryKodexAgentStorage {
            val rows = commits.groupBy { it.index }.entries.sortedBy { it.key }.mapTo(mutableListOf()) { (index, group) ->
                require(index >= 0 && group.all { it.createdAt == group.first().createdAt })
                val values = group.map { it.commit }.sortedBy { it.commitType.ordinal }
                validateCommitBatch(values)
                InMemoryRow(index, values, group.first().createdAt)
            }
            return InMemoryKodexAgentStorage(rows, agentId)
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun initialRows(initialSettings: KodexAgentSettings): MutableList<InMemoryRow> {
    val windowId = Uuid.generateV7().toString()
    val turnId = initialSettings.turnId.ifEmpty { Uuid.generateV7().toString() }
    val timestamp = Clock.System.now()
    val settings = initialSettings.copy(
        turnId = turnId,
        windowNumber = 0,
        firstWindowId = windowId,
        previousWindowId = null,
        windowId = windowId,
    )
    val commits = listOf<AgentCommit>(
        settings,
        LlmRequestMeta.Initialization(0L),
    ).sortedBy { it.commitType.ordinal }
    return mutableListOf(InMemoryRow(0, commits, timestamp))
}
