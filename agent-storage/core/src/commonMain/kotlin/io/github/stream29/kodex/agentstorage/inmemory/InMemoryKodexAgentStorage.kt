@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.inmemory

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.validateCommitBatch
import io.github.stream29.kodex.utils.ReadWriteMutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Process-local mutable storage for tests and transient agent sessions.
 */
@OptIn(ExperimentalUuidApi::class)
public actual class InMemoryKodexAgentStorage private constructor(
    initialRows: MutableList<List<AgentCommit>>,
) : MutableKodexAgentStorage {
    private val identity: Any = Any()
    private val mutex = ReadWriteMutex()
    private val rowsByIndex: MutableList<List<AgentCommit>> = initialRows

    public actual constructor(initialSettings: KodexAgentSettings) : this(
        initialRows(initialSettings),
    )

    public actual override val uri: String = "memory:${identity.hashCode().toUInt().toString(16)}"

    public actual override suspend fun latestIndex(): Int = mutex.reader.withLock {
        rowsByIndex.lastIndex
    }

    public actual override suspend fun indexesIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): List<Int> {
        if (range.isEmpty() || types.isEmpty()) return emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        return mutex.reader.withLock {
            if (rowsByIndex.isEmpty() || range.first > rowsByIndex.lastIndex) return@withLock emptyList()
            val endInclusive = minOf(range.last, rowsByIndex.lastIndex)
            buildList {
                for (idx in range.first..endInclusive) {
                    if (rowsByIndex[idx].any { it.commitType in types }) {
                        add(idx)
                    }
                }
            }
        }
    }

    public actual override suspend fun getCommitsExact(
        index: Int,
        types: Set<AgentCommitType>,
    ): List<AgentCommit> {
        require(index >= 0) { "Index $index must be non-negative." }
        if (types.isEmpty()) return emptyList()
        return mutex.reader.withLock {
            val row = rowsByIndex.getOrNull(index) ?: return@withLock emptyList()
            row.filter { it.commitType in types }
        }
    }

    public actual override suspend fun getCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): List<IndexedAgentCommit> {
        if (range.isEmpty() || types.isEmpty()) return emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        return mutex.reader.withLock {
            if (rowsByIndex.isEmpty() || range.first > rowsByIndex.lastIndex) return@withLock emptyList()
            val endInclusive = minOf(range.last, rowsByIndex.lastIndex)
            buildList {
                for (idx in range.first..endInclusive) {
                    for (commit in rowsByIndex[idx]) {
                        if (commit.commitType in types) {
                            add(IndexedAgentCommit(idx, commit))
                        }
                    }
                }
            }
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
        return mutex.reader.withLock {
            if (rowsByIndex.isEmpty() || range.first > rowsByIndex.lastIndex) return@withLock emptyList()
            val endInclusive = minOf(range.last, rowsByIndex.lastIndex)
            buildList(minOf(limit, 64)) {
                for (idx in range.first..endInclusive) {
                    for (commit in rowsByIndex[idx]) {
                        if (commit.commitType in types) {
                            add(IndexedAgentCommit(idx, commit))
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
        return mutex.reader.withLock {
            if (rowsByIndex.isEmpty() || range.first > rowsByIndex.lastIndex) return@withLock emptyList()
            val endInclusive = minOf(range.last, rowsByIndex.lastIndex)
            val reversed = ArrayList<IndexedAgentCommit>(minOf(limit, 64))
            outer@ for (idx in endInclusive downTo range.first) {
                val commitsAtIdx = rowsByIndex[idx]
                for (i in commitsAtIdx.indices.reversed()) {
                    val commit = commitsAtIdx[i]
                    if (commit.commitType in types) {
                        reversed.add(IndexedAgentCommit(idx, commit))
                        if (reversed.size == limit) break@outer
                    }
                }
            }
            reversed.asReversed()
        }
    }

    public actual override suspend fun commit(commits: List<AgentCommit>): Int {
        validateCommitBatch(commits)
        val sorted = commits.sortedBy { it.commitType.ordinal }
        return mutex.writer.withLock {
            val nextIndex = rowsByIndex.size
            rowsByIndex.add(sorted)
            nextIndex
        }
    }

    public actual override suspend fun revert(untilExclusive: Int) {
        require(untilExclusive >= 0) { "Revert boundary $untilExclusive must be non-negative." }
        mutex.writer.withLock {
            if (untilExclusive < rowsByIndex.size) {
                rowsByIndex.subList(untilExclusive, rowsByIndex.size).clear()
            }
        }
    }

    public actual companion object {
        /** Creates the uninitialized storage backing a freshly spawned AgentSession. */
        public actual fun empty(): InMemoryKodexAgentStorage =
            InMemoryKodexAgentStorage(mutableListOf())
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun initialRows(initialSettings: KodexAgentSettings): MutableList<List<AgentCommit>> {
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
        CommitTimestamp(timestamp),
        TokenCountSnapshot.Initialization(0L),
    ).sortedBy { it.commitType.ordinal }
    return mutableListOf(commits)
}
