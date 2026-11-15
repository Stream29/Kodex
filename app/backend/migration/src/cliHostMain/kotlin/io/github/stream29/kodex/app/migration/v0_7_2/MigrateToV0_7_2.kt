package io.github.stream29.kodex.app.migration.v0_7_2

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.coroutines.CoroutineCloseable
import io.github.stream29.kodex.utils.coroutines.use
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.SqliteFailure
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

internal suspend fun migrateToV0_7_2(home: Path, fileSystem: CoroutineFileSystem): Unit = withContext(Dispatchers.IO) {
    val staging = Path(home, StagingName)
    val target = Path(home, "kodex.db")
    CoroutineCloseable { cleanStaging(home, fileSystem) }.use {
        cleanStaging(home, fileSystem)
        if (fileSystem.exists(target) && matchesPublishedV0_7_2(target)) {
            syncPublishedCompactionMirrors(home, target, fileSystem)
            return@use
        }
        fileSystem.createDirectories(home)
        val sourceSnapshot = if (fileSystem.exists(target)) readSourceDatabase(target) else SourceDatabaseSnapshot.Empty
        openSqliteConnection(staging).use { connection ->
            initializeV0_7_2Schema(connection)
            importSnapshot(connection, sourceSnapshot)
            verifyStaging(connection, sourceSnapshot)
            currentCoroutineContext().ensureActive()
            val checkpoint = connection.query("PRAGMA wal_checkpoint(TRUNCATE)").single()
            if (checkpoint[0] != 0L) throw IOException("Migration staging checkpoint is busy")
            if (connection.query("PRAGMA journal_mode=DELETE").single().single() != "delete") {
                throw IOException("Migration staging journal mode did not change")
            }
        }
        cleanStaging(home, fileSystem, sidecarsOnly = true)
        currentCoroutineContext().ensureActive()
        fileSystem.atomicMove(staging, target)
        for (suffix in listOf("-wal", "-shm")) {
            fileSystem.delete(Path(home, "kodex.db$suffix"), mustExist = false)
        }
        for (mirrorUpdate in sourceSnapshot.mirrorCompactionUpdates) {
            currentCoroutineContext().ensureActive()
            val sessionRoot = Path(home, "sessions", mirrorUpdate.agentId.toString())
            val indexTimeline = timelineDirectory(sessionRoot, "index")
            val recordFile = recordPath(indexTimeline, mirrorUpdate.index)
            if (fileSystem.exists(recordFile)) {
                fileSystem.writeString(recordFile, mirrorUpdate.indexMirrorJson)
            }
        }
    }
}

private const val StagingName = ".kodex-migration-0.7.2-staging.db"
private const val FallbackTimestamp = "1970-01-01T00:00:00Z"
private const val MaxCompactionRetainedHistoryTokens: Int = 64_000
private const val ApproximateBytesPerToken: Int = 4

private data class CommitRecord(
    val agentId: Long,
    val type: String,
    val index: Long,
    val payloadJson: String,
    val createdAt: String,
)

private data class MirrorCompactionUpdate(
    val agentId: Long,
    val index: Int,
    val indexMirrorJson: String,
)

private data class ScheduleRowSnapshot(
    val scheduleId: Long,
    val agentId: Long,
    val triggerAtEpochSeconds: Long,
    val triggerAtNanoseconds: Long,
    val triggerAt: String,
    val cron: String?,
    val description: String,
    val createdAt: String,
)

private data class SourceDatabaseSnapshot(
    val sessions: List<Pair<Long, Long>>,
    val agents: List<Triple<Long, Long, Long?>>,
    val commits: List<CommitRecord>,
    val schedules: List<ScheduleRowSnapshot>,
    val mirrorCompactionUpdates: List<MirrorCompactionUpdate>,
) {
    companion object {
        val Empty = SourceDatabaseSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

private fun initializeV0_7_2Schema(connection: SqliteConnection) {
    connection.transaction {
        v0_7_2Schema.split(';').filter { it.isNotBlank() }.forEach { connection.execute(it) }
        connection.execute("PRAGMA user_version=1")
    }
}

// language=SQL
private val v0_7_2Schema: String = """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
) STRICT;

CREATE INDEX idx_sessions_archived ON sessions(archived, session_id);

CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY CHECK (agent_id BETWEEN 0 AND 2147483647),
    session_id INTEGER NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE
) STRICT;

CREATE TABLE agent_commits (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (
        type IN (
            'user_message',
            'event_ask_user',
            'event_schedule',
            'event_terminal_exited',
            'assistant_message',
            'assistant_message_commentary',
            'assistant_message_final',
            'developer_message',
            'agent_message',
            'request_user_input_tool_event',
            'suggest_subagent_task_tool_event',
            'await_event_tool_event',
            'plan_update',
            'compaction_point',
            'reasoning',
            'context_compaction',
            'command_execution_tool_event',
            'patch_tool_event',
            'mcp_tool_event',
            'web_search_tool_event',
            'image_generation_tool_event',
            'image_view_tool_event',
            'tool_search_event',
            'invalid_tool_call',
            'server_tool_search',
            'hosted_web_search',
            'hosted_image_generation',
            'json_tool_event',
            'text_tool_event',
            'custom_tool_event',
            'settings',
            'timestamp',
            'llm_request_meta_legacy',
            'llm_request_meta_initialization',
            'llm_request_meta_compaction',
            'llm_request_meta_response',
            'token_count_legacy',
            'token_count_initialization',
            'token_count_compaction',
            'token_count_response',
            'unstable',
            'steer',
            'ask_user'
        )
    ),
    "index" INTEGER NOT NULL CHECK ("index" BETWEEN 0 AND 2147483647),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    created_at TEXT NOT NULL,
    PRIMARY KEY (agent_id, type, "index")
) STRICT, WITHOUT ROWID;

CREATE INDEX commits_global_index ON agent_commits(agent_id, "index", type);
CREATE INDEX commits_settings_cwd ON agent_commits(
    json_extract(payload_json, '$.cwd'), agent_id, "index" DESC
) WHERE type = 'settings';

CREATE VIEW session_catalog (
    session_id, archived, thread_name, cwd,
    created_at, updated_at
) AS
SELECT s.session_id, s.archived,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.threadName'), '') END,
    CASE WHEN st.agent_id IS NULL THEN NULL
        ELSE coalesce(json_extract(st.payload_json, '$.cwd'), '.') END,
    (SELECT c.created_at FROM agent_commits AS c WHERE c.agent_id = s.session_id AND c."index" = 0 LIMIT 1),
    (SELECT c.created_at FROM agent_commits AS c WHERE c.agent_id = s.session_id ORDER BY c."index" DESC LIMIT 1)
FROM sessions AS s
LEFT JOIN agent_commits AS st
    ON st.agent_id = s.session_id AND st.type = 'settings'
    AND st."index" = (
        SELECT max("index") FROM agent_commits
        WHERE agent_id = s.session_id AND type = 'settings'
    );

CREATE TABLE schedules (
    schedule_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (schedule_id >= 1),
    agent_id INTEGER NOT NULL CHECK (agent_id BETWEEN 0 AND 2147483647),
    trigger_at_epoch_seconds INTEGER NOT NULL,
    trigger_at_nanoseconds INTEGER NOT NULL CHECK (trigger_at_nanoseconds BETWEEN 0 AND 999999999),
    trigger_at TEXT NOT NULL,
    cron TEXT,
    description TEXT NOT NULL,
    created_at TEXT NOT NULL
) STRICT;

CREATE INDEX idx_schedules_trigger_at
    ON schedules(trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);

CREATE INDEX idx_schedules_agent_id
    ON schedules(agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);
""".trimIndent()

private suspend fun cleanStaging(home: Path, fs: CoroutineFileSystem, sidecarsOnly: Boolean = false) {
    var failure: Throwable? = null
    for (suffix in if (sidecarsOnly) listOf("-wal", "-shm") else listOf("-wal", "-shm", "")) {
        try {
            fs.delete(Path(home, StagingName + suffix), mustExist = false)
        } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
        }
    }
    failure?.let { throw it }
}

private fun verify(condition: Boolean, message: String = "SQLite 0.7.2 migration verification failed") {
    if (!condition) throw IOException(message)
}

private suspend fun readSourceDatabase(path: Path): SourceDatabaseSnapshot {
    try {
        return openSqliteConnection(path, readOnly = true).use { db ->
            currentCoroutineContext().ensureActive()
            verify(db.query("PRAGMA integrity_check") == listOf(listOf("ok")), "Corrupt source SQLite database: $path")
            verify(db.query("PRAGMA foreign_key_check").isEmpty(), "Foreign key violation in source SQLite database: $path")
            verify(db.query("PRAGMA user_version") == listOf(listOf(1L)), "Unsupported SQLite user_version in $path")
            for (table in listOf("sessions", "agents", "agent_commits")) {
                verify(
                    db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", listOf(table)).size == 1,
                    "Missing required table $table in $path",
                )
            }
            val sessions = db.query("SELECT session_id, archived FROM sessions ORDER BY session_id").map { row ->
                (row[0] as Long) to (row[1] as Long)
            }
            val agents = db.query("SELECT agent_id, session_id, parent_agent_id FROM agents ORDER BY agent_id").map { row ->
                Triple(row[0] as Long, row[1] as Long, row[2] as Long?)
            }
            val rawCommits = db.query(
                """
                SELECT agent_id, type, "index", payload_json, created_at
                FROM agent_commits
                WHERE type NOT IN ('timestamp', 'compaction_point')
                ORDER BY agent_id, "index", type
                """.trimIndent(),
            ).map { row ->
                val createdAt = row[4] as? String ?: FallbackTimestamp
                validateTimestamp(createdAt, path)
                CommitRecord(
                    agentId = row[0] as Long,
                    type = row[1] as String,
                    index = row[2] as Long,
                    payloadJson = row[3] as String,
                    createdAt = createdAt,
                )
            }
            val hasSchedules = db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schedules'").size == 1
            val schedules = if (hasSchedules) {
                db.query(
                    """
                    SELECT schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, trigger_at, cron, description, created_at
                    FROM schedules
                    ORDER BY schedule_id
                    """.trimIndent(),
                ).map { row ->
                    ScheduleRowSnapshot(
                        scheduleId = row[0] as Long,
                        agentId = row[1] as Long,
                        triggerAtEpochSeconds = row[2] as Long,
                        triggerAtNanoseconds = row[3] as Long,
                        triggerAt = row[4] as String,
                        cron = row[5] as? String,
                        description = row[6] as String,
                        createdAt = row[7] as String,
                    )
                }
            } else {
                emptyList()
            }
            val (upgradedCommits, mirrorUpdates) = upgradeCompactionCommits(rawCommits, path)
            SourceDatabaseSnapshot(
                sessions = sessions,
                agents = agents,
                commits = upgradedCommits,
                schedules = schedules,
                mirrorCompactionUpdates = mirrorUpdates,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        throw io
    } catch (failure: SqliteFailure) {
        throw IOException("Invalid SQLite database at $path", failure)
    }
}

private val RetainedCommitWireNames: Set<String> =
    AgentCommitType.CompactionRetainedTypes.mapTo(mutableSetOf()) { it.wireName }

private fun upgradeCompactionCommits(
    commits: List<CommitRecord>,
    path: Path,
): Pair<List<CommitRecord>, List<MirrorCompactionUpdate>> {
    val upgraded = ArrayList<CommitRecord>(commits.size)
    val mirrorUpdates = mutableListOf<MirrorCompactionUpdate>()
    var currentAgentId: Long? = null
    var previousRetainedItems: List<CompactionRetainedItem> = emptyList()
    val incrementalRetained = mutableListOf<CompactionRetainedItem>()

    for (commit in commits) {
        if (commit.agentId != currentAgentId) {
            currentAgentId = commit.agentId
            previousRetainedItems = emptyList()
            incrementalRetained.clear()
        }
        when {
            commit.type in RetainedCommitWireNames -> {
                val decoded = try {
                    AgentCommitType.fromWireName(commit.type)
                        .decodePayloadJson(commit.payloadJson, OpenAiJsonCodec) as CompactionRetainedItem
                } catch (failure: Throwable) {
                    throw IOException(
                        "Invalid retained commit payload at agent=${commit.agentId}, index=${commit.index} in $path",
                        failure,
                    )
                }
                incrementalRetained += decoded
                upgraded += commit
            }

            commit.type == AgentCommitType.ContextCompaction.wireName -> {
                val rawObject = try {
                    OpenAiJsonCodec.parseToJsonElement(commit.payloadJson) as? JsonObject
                        ?: throw IOException("Expected JSON object for context_compaction")
                } catch (failure: Throwable) {
                    throw IOException(
                        "Invalid context_compaction payload at agent=${commit.agentId}, index=${commit.index} in $path",
                        failure,
                    )
                }
                val parsed = try {
                    OpenAiJsonCodec.decodeFromString(StableContextCompaction.serializer(), commit.payloadJson)
                } catch (failure: Throwable) {
                    throw IOException(
                        "Invalid context_compaction model at agent=${commit.agentId}, index=${commit.index} in $path",
                        failure,
                    )
                }
                val hasExplicitRetainedItems = "retained_items" in rawObject
                val computedRetained = if (hasExplicitRetainedItems) {
                    parsed.retainedItems
                } else {
                    mergeWithinBudget(incrementalRetained, previousRetainedItems)
                }
                val upgradedCompaction = parsed.copy(retainedItems = computedRetained)
                val upgradedPayload = upgradedCompaction.encodePayloadJson(OpenAiJsonCodec)
                upgraded += commit.copy(payloadJson = upgradedPayload)
                mirrorUpdates += MirrorCompactionUpdate(
                    agentId = commit.agentId,
                    index = commit.index.toInt(),
                    indexMirrorJson = OpenAiJsonCodec.encodeToString(CleanIndexEntry.serializer(), upgradedCompaction),
                )
                previousRetainedItems = computedRetained
                incrementalRetained.clear()
            }

            else -> {
                upgraded += commit
            }
        }
    }
    return upgraded to mirrorUpdates
}

private fun mergeWithinBudget(
    incremental: List<CompactionRetainedItem>,
    previous: List<CompactionRetainedItem>,
): List<CompactionRetainedItem> {
    var remainingTokens = MaxCompactionRetainedHistoryTokens
    val retainedReversed = mutableListOf<CompactionRetainedItem>()
    fun tryRetain(item: CompactionRetainedItem): Boolean {
        val tokens = item.approximateTokenCount()
        if (tokens > remainingTokens) return false
        remainingTokens -= tokens
        retainedReversed += item
        return true
    }
    for (index in incremental.indices.reversed()) {
        if (!tryRetain(incremental[index])) {
            return retainedReversed.asReversed()
        }
    }
    for (index in previous.indices.reversed()) {
        if (!tryRetain(previous[index])) {
            break
        }
    }
    return retainedReversed.asReversed()
}

private fun CompactionRetainedItem.approximateTokenCount(): Int =
    when (this) {
        is StableUserMessage -> content.approximateTokenCount()
        is StableAskUserEvent ->
            encodePayloadJson(OpenAiJsonCodec).approximateTokenCount()
        is StablePlanUpdate ->
            encodePayloadJson(OpenAiJsonCodec).approximateTokenCount()
        is StableRequestUserInputToolEvent ->
            encodePayloadJson(OpenAiJsonCodec).approximateTokenCount()
        is StableSuggestSubagentTaskToolEvent ->
            encodePayloadJson(OpenAiJsonCodec).approximateTokenCount()
    }
    .coerceAtLeast(1)

private fun List<ContentItem>.approximateTokenCount(): Int =
    sumOf { item ->
        when (item) {
            is ContentItem.InputText -> item.text.approximateTokenCount()
            is ContentItem.OutputText -> item.text.approximateTokenCount()
            is ContentItem.InputImage -> 0
        }
    }

private fun String.approximateTokenCount(): Int {
    val bytes = encodeToByteArray().size
    if (bytes == 0) return 0
    return (bytes + ApproximateBytesPerToken - 1) / ApproximateBytesPerToken
}

private fun validateTimestamp(raw: String, path: Path) {
    try {
        Instant.parse(raw)
    } catch (failure: IllegalArgumentException) {
        throw IOException("Invalid commit timestamp '$raw' in $path", failure)
    }
}

private suspend fun importSnapshot(db: SqliteConnection, snapshot: SourceDatabaseSnapshot) {
    db.execute("BEGIN IMMEDIATE")
    try {
        for ((sessionId, archived) in snapshot.sessions) {
            currentCoroutineContext().ensureActive()
            db.execute("INSERT INTO sessions VALUES (?, ?)", listOf(sessionId, archived))
        }
        for ((agentId, sessionId, parentAgentId) in snapshot.agents) {
            currentCoroutineContext().ensureActive()
            db.execute("INSERT INTO agents VALUES (?, ?, ?)", listOf(agentId, sessionId, parentAgentId))
        }
        for (commit in snapshot.commits) {
            currentCoroutineContext().ensureActive()
            try {
                db.execute(
                    "INSERT INTO agent_commits VALUES (?, ?, ?, ?, ?)",
                    listOf(commit.agentId, commit.type, commit.index, commit.payloadJson, commit.createdAt),
                )
            } catch (failure: SqliteFailure) {
                throw IOException("Invalid migration commit record: agent=${commit.agentId}, index=${commit.index}", failure)
            }
        }
        for (schedule in snapshot.schedules) {
            currentCoroutineContext().ensureActive()
            db.execute(
                """
                INSERT INTO schedules (
                    schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, trigger_at, cron, description, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(
                    schedule.scheduleId,
                    schedule.agentId,
                    schedule.triggerAtEpochSeconds,
                    schedule.triggerAtNanoseconds,
                    schedule.triggerAt,
                    schedule.cron,
                    schedule.description,
                    schedule.createdAt,
                ),
            )
        }
        currentCoroutineContext().ensureActive()
        db.execute("COMMIT")
    } catch (primary: Throwable) {
        try {
            db.execute("ROLLBACK")
        } catch (rollback: Throwable) {
            primary.addSuppressed(rollback)
        }
        throw primary
    }
}

private suspend fun syncPublishedCompactionMirrors(
    home: Path,
    target: Path,
    fileSystem: CoroutineFileSystem,
) {
    val updates = openSqliteConnection(target, readOnly = true).use { db ->
        db.query(
            """SELECT agent_id, "index", payload_json FROM agent_commits WHERE type = 'context_compaction' ORDER BY agent_id, "index"""",
        ).map { row ->
            val agentId = row[0] as Long
            val index = (row[1] as Long).toInt()
            val payloadJson = row[2] as String
            val parsed = OpenAiJsonCodec.decodeFromString(StableContextCompaction.serializer(), payloadJson)
            MirrorCompactionUpdate(
                agentId = agentId,
                index = index,
                indexMirrorJson = OpenAiJsonCodec.encodeToString(CleanIndexEntry.serializer(), parsed),
            )
        }
    }
    for (mirrorUpdate in updates) {
        currentCoroutineContext().ensureActive()
        val sessionRoot = Path(home, "sessions", mirrorUpdate.agentId.toString())
        val indexTimeline = timelineDirectory(sessionRoot, "index")
        val recordFile = recordPath(indexTimeline, mirrorUpdate.index)
        if (fileSystem.exists(recordFile)) {
            fileSystem.writeString(recordFile, mirrorUpdate.indexMirrorJson)
        }
    }
}

private suspend fun matchesPublishedV0_7_2(path: Path): Boolean {
    try {
        openSqliteConnection(path, readOnly = true).use { db ->
            verifyV0_7_2Structure(db)
        }
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SqliteFailure) {
        return false
    } catch (_: IOException) {
        return false
    }
}

private suspend fun verifyV0_7_2Structure(db: SqliteConnection) {
    currentCoroutineContext().ensureActive()
    verify(db.query("PRAGMA integrity_check") == listOf(listOf("ok")))
    verify(db.query("PRAGMA foreign_key_check").isEmpty())
    verify(db.query("PRAGMA user_version") == listOf(listOf(1L)))
    for ((name, type) in listOf(
        "sessions" to "table",
        "agents" to "table",
        "agent_commits" to "table",
        "schedules" to "table",
        "idx_sessions_archived" to "index",
        "commits_global_index" to "index",
        "commits_settings_cwd" to "index",
        "idx_schedules_trigger_at" to "index",
        "idx_schedules_agent_id" to "index",
        "session_catalog" to "view",
    )) {
        verify(db.query("SELECT 1 FROM sqlite_master WHERE name = ? AND type = ?", listOf(name, type)).size == 1)
    }
    val createdAtColumn = db.query("PRAGMA table_info(agent_commits)").firstOrNull { it.getOrNull(1) == "created_at" }
    verify(createdAtColumn != null && createdAtColumn[2] == "TEXT" && createdAtColumn[3] == 1L)
    val schedulesSql = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'schedules'")
        .singleOrNull()?.singleOrNull() as? String ?: ""
    verify("AUTOINCREMENT" in schedulesSql.uppercase())
    val tableSql = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'agent_commits'")
        .singleOrNull()?.singleOrNull() as? String ?: ""
    verify(
        "llm_request_meta_response" in tableSql &&
            "'steer'" in tableSql &&
            "'ask_user'" in tableSql &&
            "'event_ask_user'" in tableSql &&
            "'event_schedule'" in tableSql &&
            "'event_terminal_exited'" in tableSql &&
            "'await_event_tool_event'" in tableSql,
    )
    verify(
        db.query(
            """
            SELECT count(*)
            FROM agent_commits
            WHERE type = 'context_compaction'
              AND json_type(payload_json, '$.retained_items') IS NOT 'array'
            """.trimIndent(),
        ) == listOf(listOf(0L)),
    )
}

private suspend fun verifyStaging(db: SqliteConnection, snapshot: SourceDatabaseSnapshot) {
    verifyV0_7_2Structure(db)
    verify(
        db.query("SELECT session_id, archived FROM sessions ORDER BY session_id") ==
            snapshot.sessions.map { listOf(it.first, it.second) },
    )
    verify(
        db.query("SELECT agent_id, session_id, parent_agent_id FROM agents ORDER BY agent_id") ==
            snapshot.agents.map { listOf(it.first, it.second, it.third) },
    )
    verify(
        db.query("SELECT count(*) FROM agent_commits") == listOf(listOf(snapshot.commits.size.toLong())),
    )
    verify(
        db.query("SELECT session_id FROM session_catalog ORDER BY session_id") ==
            snapshot.sessions.map { listOf(it.first) },
    )
}
