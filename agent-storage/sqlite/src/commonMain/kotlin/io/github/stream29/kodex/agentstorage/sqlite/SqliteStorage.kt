@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.validateCommitBatch
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public actual class SqliteAgentStorage actual constructor(
    internal val connection: SqliteConnection,
    agentId: Int,
    public actual override val uri: String,
    private val mutex: Mutex,
    private val requireAccess: () -> Unit,
) : MutableKodexAgentStorage {
    internal val agentId: Long = agentId.also { require(it >= 0) { "Invalid agent id: $it" } }.toLong()

    internal suspend fun <T> read(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock { withContext(Dispatchers.IO) { requireAccess(); block() } }
    }

    internal suspend fun <T> write(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    requireAccess()
                    connection.transaction {
                        block()
                    }
                }
            }
        }
    }

    private fun tailUnsafe(): Int =
        connection.query(
            """SELECT coalesce(max("index"), -1) FROM agent_commits WHERE agent_id = ?""",
            listOf(agentId),
        ).firstOrNull()?.let { (it[0] as Long).toInt() } ?: -1

    public actual override suspend fun latestIndex(): Int = read { tailUnsafe() }

    public actual override suspend fun indexesIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): List<Int> = read {
        if (range.isEmpty() || types.isEmpty()) return@read emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        val (clause, params) = typeFilterClause(types)
        val rows = connection.query(
            """SELECT DISTINCT "index" FROM agent_commits WHERE agent_id = ? AND "index" BETWEEN ? AND ?$clause ORDER BY "index" ASC""",
            listOf(agentId, range.first.toLong(), range.last.toLong()) + params,
        )
        rows.map { (it[0] as Long).toInt() }
    }

    public actual override suspend fun getCommitsExact(
        index: Int,
        types: Set<AgentCommitType>,
    ): List<AgentCommit> = read {
        require(index >= 0) { "Index $index must be non-negative." }
        if (types.isEmpty()) return@read emptyList()
        val (clause, params) = typeFilterClause(types)
        val rows = connection.query(
            """SELECT type, payload_json FROM agent_commits WHERE agent_id = ? AND "index" = ?$clause""",
            listOf(agentId, index.toLong()) + params,
        )
        rows.map { row ->
            val type = AgentCommitType.fromWireName(row[0] as String)
            type.decodePayloadJson(row[1] as String, OpenAiJsonCodec)
        }.sortedBy { it.commitType.ordinal }
    }

    public actual override suspend fun getCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): List<IndexedAgentCommit> = read {
        if (range.isEmpty() || types.isEmpty()) return@read emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        val (clause, params) = typeFilterClause(types)
        val rows = connection.query(
            """SELECT "index", type, payload_json FROM agent_commits WHERE agent_id = ? AND "index" BETWEEN ? AND ?$clause ORDER BY "index" ASC""",
            listOf(agentId, range.first.toLong(), range.last.toLong()) + params,
        )
        decodeIndexedRows(rows)
    }

    public actual override suspend fun getFirstCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> = read {
        require(limit >= 0) { "Limit $limit must be non-negative." }
        if (limit == 0 || range.isEmpty() || types.isEmpty()) return@read emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        val (clause, params) = typeFilterClause(types)
        val sqlLimit = boundedIndexScanLimit(limit, types)
        val rows = connection.query(
            """SELECT "index", type, payload_json FROM agent_commits WHERE agent_id = ? AND "index" BETWEEN ? AND ?$clause ORDER BY "index" ASC, type ASC LIMIT ?""",
            listOf(agentId, range.first.toLong(), range.last.toLong()) + params + listOf(sqlLimit),
        )
        decodeIndexedRows(rows).take(limit)
    }

    public actual override suspend fun getLastCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> = read {
        require(limit >= 0) { "Limit $limit must be non-negative." }
        if (limit == 0 || range.isEmpty() || types.isEmpty()) return@read emptyList()
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        val (clause, params) = typeFilterClause(types)
        val sqlLimit = boundedIndexScanLimit(limit, types)
        val rows = connection.query(
            """SELECT "index", type, payload_json FROM agent_commits WHERE agent_id = ? AND "index" BETWEEN ? AND ?$clause ORDER BY "index" DESC, type DESC LIMIT ?""",
            listOf(agentId, range.first.toLong(), range.last.toLong()) + params + listOf(sqlLimit),
        )
        decodeIndexedRows(rows).takeLast(limit)
    }

    public actual override suspend fun commit(commits: List<AgentCommit>): Int {
        validateCommitBatch(commits)
        val sorted = commits.sortedBy { it.commitType.ordinal }
        val encoded = sorted.map { commit ->
            commit.commitType.wireName to commit.encodePayloadJson(OpenAiJsonCodec)
        }
        return write {
            val nextIndex = tailUnsafe() + 1
            for ((wireType, payloadJson) in encoded) {
                connection.execute(
                    "INSERT INTO agent_commits (agent_id, type, \"index\", payload_json) VALUES (?, ?, ?, ?)",
                    listOf(agentId, wireType, nextIndex.toLong(), payloadJson),
                )
            }
            nextIndex
        }
    }

    public actual override suspend fun revert(untilExclusive: Int) {
        require(untilExclusive >= 0) { "Revert boundary $untilExclusive must be non-negative." }
        write {
            connection.execute(
                """DELETE FROM agent_commits WHERE agent_id = ? AND "index" >= ?""",
                listOf(agentId, untilExclusive.toLong()),
            )
        }
    }
}

private fun typeFilterClause(types: Set<AgentCommitType>): Pair<String, List<String>> {
    if (types.size == AgentCommitType.All.size) return "" to emptyList()
    val wireNames = types.map { it.wireName }
    return if (wireNames.size == 1) {
        " AND type = ?" to wireNames
    } else {
        " AND type IN (${wireNames.joinToString(",") { "?" }})" to wireNames
    }
}

private fun boundedIndexScanLimit(limit: Int, types: Set<AgentCommitType>): Long {
    // At any single index there is at most one commit per legacyTimelineName category.
    // Fetching (limit + categoryCount - 1) rows in index order guarantees all matching
    // rows at the boundary index are present before ordinal sorting and truncation.
    val categoryCount = types.mapTo(mutableSetOf()) { it.legacyTimelineName }.size.coerceAtLeast(1)
    return limit.toLong() + (categoryCount - 1).toLong()
}

private fun decodeIndexedRows(rows: List<List<Any?>>): List<IndexedAgentCommit> {
    if (rows.isEmpty()) return emptyList()
    val decoded = rows.map { row ->
        val index = (row[0] as Long).toInt()
        val type = AgentCommitType.fromWireName(row[1] as String)
        IndexedAgentCommit(index, type.decodePayloadJson(row[2] as String, OpenAiJsonCodec))
    }
    return decoded.sortedWith(compareBy<IndexedAgentCommit> { it.index }.thenBy { it.commit.commitType.ordinal })
}
