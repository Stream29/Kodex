@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentstorage.sqlite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.contract.MutableObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.validateCommitBatch
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

private data class TimelineState(val latestIndex: Int, val nonce: Long)
private data class HistoryChange(val timeline: Boolean, val catalog: Boolean)

public actual class SqliteAgentStorage actual constructor(
    internal val connection: SqliteConnection,
    public actual override val agentId: Int,
    private val mutex: Mutex,
    private val dispatcher: CoroutineDispatcher,
    private val requireAccess: () -> Unit,
    private val onHistoryChanged: ((Set<AgentCommitType>?) -> Unit)?,
    private val onSessionGroupsChanged: ((Map<String?, Boolean>) -> Unit)?,
) : MutableObservableKodexAgentStorage {
    internal val sqlAgentId: Long = agentId.also { require(it >= 0) { "Invalid agent id: $it" } }.toLong()
    private val initialState = readTimelineState()
    private val observableTail = MutableStateFlow(initialState.latestIndex)
    private val observableNonce = MutableStateFlow(initialState.nonce)
    public actual override val latestIndexFlow: StateFlow<Int> = observableTail.asStateFlow()
    public actual override val cacheNonce: StateFlow<Long> = observableNonce.asStateFlow()

    private fun readTimelineState(): TimelineState {
        val (index, nonce) = connection.query(
            """SELECT (SELECT coalesce(max("index"), -1) FROM agent_commits WHERE agent_id = agents.agent_id), cache_nonce
                FROM agents WHERE agent_id = ?""",
            listOf(sqlAgentId),
        ).single()
        return TimelineState((index as Long).toInt(), nonce as Long)
    }

    private fun publishTimelineState(state: TimelineState) {
        observableNonce.value = state.nonce
        observableTail.value = state.latestIndex
    }

    public actual suspend fun refreshObservableState(): Unit = read { publishTimelineState(readTimelineState()) }

    internal suspend fun <T> read(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock { withContext(dispatcher) { requireAccess(); block() } }
    }

    private suspend fun write(
        changedTypes: Set<AgentCommitType>?,
        block: () -> HistoryChange,
    ): Int {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(NonCancellable) {
                withContext(dispatcher) {
                    requireAccess()
                    val (timeline, groups, changed) = connection.transaction {
                        val before = connection.sessionCatalogGroup(agentId)
                        val (changed, changedCatalog) = block()
                        val groups = if (changedCatalog && before != null) {
                            connection.recordSessionGroupMutation(before, connection.sessionCatalogGroup(agentId))
                        } else emptyMap()
                        Triple(readTimelineState(), groups, changed)
                    }
                    publishTimelineState(timeline)
                    if (groups.isNotEmpty()) onSessionGroupsChanged?.invoke(groups)
                    if (changed) onHistoryChanged?.invoke(changedTypes)
                    timeline.latestIndex
                }
            }
        }
    }

    private fun tailUnsafe(): Int =
        connection.query(
            """SELECT coalesce(max("index"), -1) FROM agent_commits WHERE agent_id = ?""",
            listOf(sqlAgentId),
        ).firstOrNull()?.let { (it[0] as Long).toInt() } ?: -1

    public actual override suspend fun latestIndex(): Int = read { tailUnsafe() }

    public actual override suspend fun cacheNonce(): Long = read {
        connection.query("SELECT cache_nonce FROM agents WHERE agent_id = ?", listOf(sqlAgentId)).single().single() as Long
    }

    public actual override suspend fun countCommitsIn(
        range: IntRange,
        types: Set<AgentCommitType>,
    ): Int = read {
        if (range.isEmpty() || types.isEmpty()) return@read 0
        require(range.first >= 0) { "Index lower bound ${range.first} must be non-negative." }
        val (clause, params) = typeFilterClause(types)
        val rows = connection.query(
            """SELECT count(*) FROM agent_commits WHERE agent_id = ? AND "index" BETWEEN ? AND ?$clause""",
            listOf(sqlAgentId, range.first.toLong(), range.last.toLong()) + params,
        )
        rows.firstOrNull()?.let { (it[0] as Long).toInt() } ?: 0
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
            """SELECT "index", type, payload_json, created_at FROM agent_commits WHERE agent_id = ? AND "index" BETWEEN ? AND ?$clause ORDER BY "index" ASC, type ASC LIMIT ?""",
            listOf(sqlAgentId, range.first.toLong(), range.last.toLong()) + params + listOf(sqlLimit),
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
            """SELECT "index", type, payload_json, created_at FROM agent_commits WHERE agent_id = ? AND "index" BETWEEN ? AND ?$clause ORDER BY "index" DESC, type DESC LIMIT ?""",
            listOf(sqlAgentId, range.first.toLong(), range.last.toLong()) + params + listOf(sqlLimit),
        )
        decodeIndexedRows(rows).takeLast(limit)
    }

    public actual override suspend fun commit(commits: List<AgentCommit>): Int =
        commit(commits, Clock.System.now())

    public actual override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        validateCommitBatch(commits)
        val sorted = commits.sortedBy { it.commitType.ordinal }
        val encoded = sorted.map { commit ->
            commit.commitType.wireName to commit.encodePayloadJson(OpenAiJsonCodec)
        }
        val timestampStr = timestamp.toString()
        return write(onHistoryChanged?.let { sorted.mapTo(mutableSetOf()) { it.commitType } }) {
            val nextIndex = tailUnsafe() + 1
            for ((wireType, payloadJson) in encoded) {
                connection.execute(
                    """INSERT INTO agent_commits (agent_id, type, "index", payload_json, created_at) VALUES (?, ?, ?, ?, ?)""",
                    listOf(sqlAgentId, wireType, nextIndex.toLong(), payloadJson, timestampStr),
                )
            }
            connection.appendSessionCatalog(sqlAgentId, nextIndex, timestampStr, sorted.filterIsInstance<KodexAgentSettings>().singleOrNull())
            HistoryChange(timeline = true, catalog = true)
        }
    }

    public actual override suspend fun revert(untilExclusive: Int) {
        require(untilExclusive >= 0) { "Revert boundary $untilExclusive must be non-negative." }
        val emptySteerJson = SteerSnapshot(emptyList()).encodePayloadJson(OpenAiJsonCodec)
        write(changedTypes = null) {
            connection.execute(
                """DELETE FROM agent_commits WHERE agent_id = ? AND "index" >= ?""",
                listOf(sqlAgentId, untilExclusive.toLong()),
            )
            val changed = connection.changes() != 0
            connection.execute(
                """UPDATE agent_commits SET payload_json = ?
                    WHERE agent_id = ? AND type = ? AND json_array_length(payload_json) > 0""",
                listOf(emptySteerJson, sqlAgentId, AgentCommitType.Steer.wireName),
            )
            val clearedSteer = connection.changes() != 0
            if (changed || clearedSteer) {
                connection.execute("UPDATE agents SET cache_nonce = cache_nonce + 1 WHERE agent_id = ?", listOf(sqlAgentId))
            }
            if (changed) connection.recomputeSessionCatalog(agentId)
            HistoryChange(timeline = changed || clearedSteer, catalog = changed)
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
    val categoryCount = types.mapTo(mutableSetOf()) { it.legacyTimelineName }.size.coerceAtLeast(1)
    return limit.toLong() + (categoryCount - 1).toLong()
}

private fun decodeIndexedRows(rows: List<List<Any?>>): List<IndexedAgentCommit> {
    if (rows.isEmpty()) return emptyList()
    val decoded = rows.map { row ->
        val index = (row[0] as Long).toInt()
        val type = AgentCommitType.fromWireName(row[1] as String)
        val createdAt = (row.getOrNull(3) as? String)?.let { Instant.parse(it) } ?: Instant.DISTANT_PAST
        IndexedAgentCommit(index, type.decodePayloadJson(row[2] as String, OpenAiJsonCodec), createdAt)
    }
    return decoded.sortedWith(compareBy<IndexedAgentCommit> { it.index }.thenBy { it.commit.commitType.ordinal })
}
