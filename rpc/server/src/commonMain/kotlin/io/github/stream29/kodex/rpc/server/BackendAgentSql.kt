package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentstorage.sqlite.SqliteSchemaVersion
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.sqlite.SqliteReadFailure
import io.github.stream29.kodex.utils.sqlite.SqliteReadFailureKind
import io.github.stream29.kodex.utils.sqlite.SqliteReadPolicy
import io.github.stream29.kodex.utils.sqlite.SqliteReadRows
import io.github.stream29.kodex.utils.sqlite.withSqliteReadQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

internal class BackendAgentSql(private val database: Path) {
    private val slot = Mutex()
    private val policy = SqliteReadPolicy(
        AgentSqlSchema.relations.mapTo(mutableSetOf()) { it.name },
        AgentSqlSchema.functions.toSet(),
        AgentSqlInputBytes,
        AgentSqlCellBytes,
        requiredUserVersion = SqliteSchemaVersion,
    )

    fun schema(): AgentSqlSchemaResult = AgentSqlSchema

    suspend fun query(sql: String): AgentSqlQueryResult {
        val started = TimeSource.Monotonic.markNow()
        if (!slot.tryLock()) return failed(started, AgentSqlError.BUSY, "Another SQL query is running on this backend.")
        return try {
            val deadline = started + AgentSqlDeadlineMillis.milliseconds
            val reply = withSqliteReadQuery(database, sql, policy, deadline) { input ->
                readResult(input, started)
            }
            if (deadline.hasPassedNow()) failed(started, AgentSqlError.TIMEOUT, "Query deadline exceeded.")
            else reply
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SqliteReadFailure) {
            when (failure.kind) {
                SqliteReadFailureKind.Unavailable -> failed(started, AgentSqlError.UNAVAILABLE, "The selected backend database is unavailable.")
                SqliteReadFailureKind.IncompatibleSchema -> failed(started, AgentSqlError.UNAVAILABLE, "The selected backend database needs a compatible Kodex Home schema.")
                SqliteReadFailureKind.Denied -> failed(started, AgentSqlError.DENIED, "The query is outside the public read-only model.")
                SqliteReadFailureKind.InvalidStatement -> failed(started, AgentSqlError.INVALID_INPUT, "Pass one valid read-only SQL statement.")
                SqliteReadFailureKind.Timeout -> failed(started, AgentSqlError.TIMEOUT, "Query deadline exceeded.")
                SqliteReadFailureKind.ValueTooLarge -> failed(started, AgentSqlError.VALUE_TOO_LARGE, "A cell or row exceeds the result allowance.")
                SqliteReadFailureKind.Failed -> failed(started, AgentSqlError.QUERY_FAILED, "SQLite did not complete the query.")
            }
        } catch (_: Exception) {
            failed(started, AgentSqlError.QUERY_FAILED, "SQLite did not complete the query.")
        } finally {
            slot.unlock()
        }
    }

    private fun readResult(source: SqliteReadRows, started: TimeMark): AgentSqlQueryResult {
        val columns = source.columns
        val rows = mutableListOf<List<JsonElement>>()
        // Reserve four digits for elapsed_ms and the longer spelling of the two booleans.
        val framing = AgentSqlResultJson.encodeToString(
            AgentSqlQueryResult.serializer(), AgentSqlQueryResult(AgentSqlDeadlineMillis, columns, emptyList(), truncated = false),
        ).encodeToByteArray().size
        fun oversized(): Nothing = throw SqliteReadFailure(SqliteReadFailureKind.ValueTooLarge)
        if (framing > AgentSqlResultBytes) oversized()
        var encoded = framing
        var truncated = false
        while (source.next()) {
            if (rows.size == AgentSqlResultRows) {
                truncated = true
                break
            }
            val row = mutableListOf<JsonElement>()
            var rowSize = 2 // Array delimiters.
            for (index in columns.indices) {
                val cell = source.cell(index).toSqlJson()
                rowSize += AgentSqlResultJson.encodeToString(JsonElement.serializer(), cell).encodeToByteArray().size
                if (index != 0) ++rowSize
                source.checkBudget()
                if (rowSize > AgentSqlResultBytes - framing) oversized()
                row += cell
            }
            val addition = rowSize + if (rows.isEmpty()) 0 else 1
            if (encoded + addition > AgentSqlResultBytes) {
                truncated = true
                break
            }
            rows += row
            encoded += addition
        }
        return AgentSqlQueryResult(started.elapsedNow().inWholeMilliseconds, columns, rows, truncated)
    }
}

private fun failed(mark: TimeMark, code: AgentSqlError, message: String): AgentSqlQueryResult =
    AgentSqlQueryResult(mark.elapsedNow().inWholeMilliseconds, error = AgentSqlDiagnostic(code, message))

private fun Any?.toSqlJson(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Long -> buildJsonObject { put("type", "integer"); put("value", this@toSqlJson.toString()) }
    is ByteArray -> buildJsonObject { put("type", "blob"); put("base64", Base64.Default.encode(this@toSqlJson)) }
    is Double -> if (isFinite()) JsonPrimitive(this) else buildJsonObject {
        put("type", "real")
        put("value", if (this@toSqlJson > 0) "+infinity" else if (this@toSqlJson < 0) "-infinity" else "nan")
    }
    else -> error("Unexpected SQLite value.")
}

private fun integer(name: String, nullable: Boolean = false) = AgentSqlColumn(name, "INTEGER", nullable)
private fun text(name: String, nullable: Boolean = false) = AgentSqlColumn(name, "TEXT", nullable)

private val AgentSqlSchema = AgentSqlSchemaResult(
    relations = listOf(
        AgentSqlRelation("sessions", false, listOf(integer("session_id")), listOf("session_id"), emptyList()),
        AgentSqlRelation("agents", false,
            listOf(integer("agent_id"), integer("session_id"), integer("parent_agent_id", true), integer("completed")),
            listOf("agent_id"), listOf("idx_agents_root_session(session_id) WHERE parent_agent_id IS NULL")),
        AgentSqlRelation("agent_commits", false,
            listOf(integer("agent_id"), text("type"), integer("index"), text("payload_json"), text("created_at")),
            listOf("agent_id", "type", "index"),
            listOf("commits_global_index(agent_id, index, type)", "commits_settings_cwd(json_extract(payload_json, '$.cwd'), agent_id, index DESC) WHERE type='settings'")),
        AgentSqlRelation("session_catalog_view", false,
            listOf(integer("root_agent_id"), text("name", true), text("cwd", true),
                text("created_at", true), text("updated_at", true)), listOf("root_agent_id"), emptyList()),
    ),
    functions = listOf("avg", "coalesce", "count", "json_extract", "max", "min", "round", "sum"),
    payloadPaths = mapOf(
        "settings" to listOf("$.name", "$.cwd"),
        "event_terminal_exited" to listOf("$.session_id", "$.exit_code"),
        "llm_request_meta_response" to listOf("$.total_tokens", "$.latency_ms"),
    ),
    examples = listOf(
        AgentSqlExample("Recently updated archived sessions (root agents.completed = 1)",
            "SELECT a.session_id, c.name, c.cwd, c.updated_at FROM session_catalog_view AS c JOIN agents AS a ON a.agent_id = c.root_agent_id WHERE a.completed=1 ORDER BY c.updated_at DESC, a.session_id DESC LIMIT 30"),
        AgentSqlExample("Latest settings name for Agent 12",
            """SELECT coalesce(json_extract(payload_json,'$.name'), '') AS name FROM agent_commits WHERE agent_id=12 AND type='settings' ORDER BY "index" DESC LIMIT 1"""),
        AgentSqlExample("Recent terminal exits for Agent 12",
            """SELECT "index", json_extract(payload_json,'$.session_id') AS terminal_id, json_extract(payload_json,'$.exit_code') AS exit_code FROM agent_commits WHERE agent_id=12 AND type='event_terminal_exited' AND "index">=0 ORDER BY "index" DESC LIMIT 30"""),
        AgentSqlExample("Latency and total tokens for requests by Agent 12",
            """SELECT "index", json_extract(payload_json,'$.latency_ms') AS latency_ms, json_extract(payload_json,'$.total_tokens') AS total_tokens FROM agent_commits WHERE agent_id=12 AND type='llm_request_meta_response' AND "index">=0 ORDER BY "index" DESC LIMIT 30"""),
    ),
)
