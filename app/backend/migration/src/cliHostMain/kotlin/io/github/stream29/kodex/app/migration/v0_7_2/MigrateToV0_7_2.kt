package io.github.stream29.kodex.app.migration.v0_7_2

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.decodePayloadJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.encodePayloadJson
import io.github.stream29.kodex.agentstorage.filesystemlayout.latestIndexPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.agentstorage.filesystemlayout.timelineDirectory
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
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

internal suspend fun migrateToV0_7_2(home: Path, fileSystem: CoroutineFileSystem): Unit = withContext(Dispatchers.IO) {
    val target = Path(home, "kodex.db")
    if (fileSystem.exists(home)) {
        migrateSettingsHooksToV0_7_2(home, fileSystem)
    }
    if (fileSystem.exists(target) && matchesPublishedV0_7_2(target)) {
        reconcileMirrorsOnReentry(home, target, fileSystem)
        return@withContext
    }
    fileSystem.createDirectories(home)
    if (!fileSystem.exists(target)) {
        openSqliteConnection(target).use { connection ->
            initializeFreshV0_7_2Database(connection)
            finalizeRollbackJournal(connection)
        }
        cleanupWalSidecars(home, fileSystem)
        return@withContext
    }

    val mirrorDeltas = linkedMapOf<Long, AgentMirrorDelta>()
    try {
        openSqliteConnection(target).use { connection ->
            connection.execute("PRAGMA synchronous=NORMAL")
            connection.execute("PRAGMA cache_size=-65536")
            validateSourceDatabaseMetadata(connection, target)
            upgradeExistingDatabaseInPlace(connection, target, mirrorDeltas)
            verifyV0_7_2SchemaMetadata(connection)
            verify(connection.query("PRAGMA foreign_key_check").isEmpty(), "Foreign key violation after 0.7.2 migration")
            finalizeRollbackJournal(connection)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        throw io
    } catch (failure: SqliteFailure) {
        throw IOException("Failed migrating SQLite database $target to 0.7.2", failure)
    }
    cleanupWalSidecars(home, fileSystem)
    applyMirrorDeltas(home, mirrorDeltas, fileSystem)
}

private const val FallbackTimestamp = "1970-01-01T00:00:00Z"
private const val MaxCompactionRetainedHistoryTokens: Int = 64_000
private const val ApproximateBytesPerToken: Int = 4

private val RetiredToolCommitTypes: Set<String> = setOf(
    "image_view_tool_event",
    "request_user_input_tool_event",
    "suggest_subagent_task_tool_event",
    "mcp_tool_event",
    "web_search_tool_event",
    "image_generation_tool_event",
    "tool_search_event",
)

internal data class CommitRecord(
    val agentId: Long,
    val type: String,
    val index: Long,
    val payloadJson: String,
    val createdAt: String,
)

private class AgentMirrorDelta {
    val upsertsByTimeline: MutableMap<String, MutableMap<Int, String>> = linkedMapOf()
    val deletesByTimeline: MutableMap<String, MutableSet<Int>> = linkedMapOf()

    fun putUpsert(timeline: String, index: Int, jsonText: String) {
        upsertsByTimeline.getOrPut(timeline) { linkedMapOf() }[index] = jsonText
        deletesByTimeline[timeline]?.remove(index)
    }

    fun putDelete(timeline: String, index: Int) {
        if (upsertsByTimeline[timeline]?.containsKey(index) == true) return
        deletesByTimeline.getOrPut(timeline) { linkedSetOf() }.add(index)
    }

    val isEmpty: Boolean
        get() = upsertsByTimeline.values.all { it.isEmpty() } && deletesByTimeline.values.all { it.isEmpty() }
}

private fun initializeFreshV0_7_2Database(connection: SqliteConnection) {
    connection.transaction {
        v0_7_2FullSchema.split(';').filter { it.isNotBlank() }.forEach { connection.execute(it) }
        connection.execute("PRAGMA user_version=1")
    }
}

private suspend fun finalizeRollbackJournal(connection: SqliteConnection) {
    currentCoroutineContext().ensureActive()
    val checkpoint = connection.query("PRAGMA wal_checkpoint(TRUNCATE)").single()
    if (checkpoint[0] != 0L) throw IOException("Migration WAL checkpoint is busy")
    if (connection.query("PRAGMA journal_mode=DELETE").single().single() != "delete") {
        throw IOException("Migration journal mode did not change to DELETE")
    }
    connection.execute("PRAGMA synchronous=FULL")
}

private suspend fun cleanupWalSidecars(home: Path, fileSystem: CoroutineFileSystem) {
    for (suffix in listOf("-wal", "-shm")) {
        fileSystem.delete(Path(home, "kodex.db$suffix"), mustExist = false)
    }
}

// language=SQL
private val v0_7_2AgentCommitsTableSql: String = """
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
            'await_event_tool_event',
            'plan_update',
            'reasoning',
            'context_compaction',
            'command_execution_tool_event',
            'patch_tool_event',
            'read_media_tool_event',
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
) STRICT, WITHOUT ROWID
""".trimIndent()

// language=SQL
private val v0_7_2SchedulesSchema: String = """
CREATE TABLE IF NOT EXISTS schedules (
    schedule_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (schedule_id >= 1),
    agent_id INTEGER NOT NULL CHECK (agent_id BETWEEN 0 AND 2147483647),
    trigger_at_epoch_seconds INTEGER NOT NULL,
    trigger_at_nanoseconds INTEGER NOT NULL CHECK (trigger_at_nanoseconds BETWEEN 0 AND 999999999),
    trigger_at TEXT NOT NULL,
    cron TEXT,
    description TEXT NOT NULL,
    created_at TEXT NOT NULL
) STRICT;

CREATE INDEX IF NOT EXISTS idx_schedules_trigger_at
    ON schedules(trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);

CREATE INDEX IF NOT EXISTS idx_schedules_agent_id
    ON schedules(agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);
""".trimIndent()

// language=SQL
private val v0_7_2FullSchema: String = """
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

$v0_7_2AgentCommitsTableSql;

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

$v0_7_2SchedulesSchema
""".trimIndent()

private fun verify(condition: Boolean, message: String = "SQLite 0.7.2 migration verification failed") {
    if (!condition) throw IOException(message)
}

private suspend fun validateSourceDatabaseMetadata(db: SqliteConnection, path: Path) {
    currentCoroutineContext().ensureActive()
    verify(db.query("PRAGMA user_version") == listOf(listOf(1L)), "Unsupported SQLite user_version in $path")
    verify(db.query("PRAGMA foreign_key_check").isEmpty(), "Foreign key violation in source SQLite database: $path")
    for (table in listOf("sessions", "agents", "agent_commits")) {
        verify(
            db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", listOf(table)).size == 1,
            "Missing required table $table in $path",
        )
    }
}

private suspend fun upgradeExistingDatabaseInPlace(
    db: SqliteConnection,
    sourcePath: Path,
    mirrorDeltas: MutableMap<Long, AgentMirrorDelta>,
) {
    val agentIds = db.query("SELECT agent_id FROM agents ORDER BY agent_id").map { it[0] as Long }

    db.execute("BEGIN IMMEDIATE")
    try {
        currentCoroutineContext().ensureActive()
        db.execute("PRAGMA writable_schema = ON")
        db.execute(
            "UPDATE sqlite_master SET sql = ? WHERE type = 'table' AND name = 'agent_commits'",
            listOf(v0_7_2AgentCommitsTableSql),
        )
        val currentSchemaVersion = db.query("PRAGMA schema_version").single().single() as Long
        db.execute("PRAGMA schema_version = ${currentSchemaVersion + 1}")
        db.execute("PRAGMA writable_schema = RESET")

        v0_7_2SchedulesSchema.split(';').filter { it.isNotBlank() }.forEach { db.execute(it) }

        if (agentIds.isNotEmpty()) {
            migrateAgentsInPlace(db, agentIds, sourcePath, mirrorDeltas)
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

private suspend fun migrateAgentsInPlace(
    db: SqliteConnection,
    agentIds: List<Long>,
    sourcePath: Path,
    mirrorDeltas: MutableMap<Long, AgentMirrorDelta>,
) {
    db.prepare(
        """
        SELECT type, "index", payload_json, coalesce(created_at, '$FallbackTimestamp')
        FROM agent_commits
        WHERE agent_id = ?
          AND type IN (
              'ask_user',
              'user_message',
              'event_ask_user',
              'plan_update',
              'context_compaction',
              'invalid_tool_call',
              'image_view_tool_event',
              'request_user_input_tool_event',
              'suggest_subagent_task_tool_event',
              'mcp_tool_event',
              'web_search_tool_event',
              'image_generation_tool_event',
              'tool_search_event'
          )
        """.trimIndent(),
    ).use { selectNonUnstableStmt ->
        db.prepare(
            """
            SELECT "index", payload_json, coalesce(created_at, '$FallbackTimestamp')
            FROM agent_commits
            WHERE agent_id = ? AND type = 'unstable'
            ORDER BY "index" DESC
            LIMIT 1
            """.trimIndent(),
        ).use { selectTailUnstableStmt ->
            db.prepare(
                """
                SELECT "index", payload_json, coalesce(created_at, '$FallbackTimestamp')
                FROM agent_commits
                WHERE agent_id = ? AND type = 'unstable'
                  AND (
                      instr(payload_json, 'image_view') > 0 OR
                      instr(payload_json, 'request_user_input') > 0 OR
                      instr(payload_json, 'suggest_subagent_task') > 0 OR
                      instr(payload_json, '"mcp"') > 0 OR
                      instr(payload_json, 'web_search') > 0 OR
                      instr(payload_json, 'image_generation') > 0 OR
                      instr(payload_json, 'tool_search') > 0
                  )
                """.trimIndent(),
            ).use { selectDirtyUnstableStmt ->
                db.prepare(
                    """
                    DELETE FROM agent_commits
                    WHERE agent_id = ?
                      AND type IN (
                          'timestamp',
                          'compaction_point',
                          'image_view_tool_event',
                          'request_user_input_tool_event',
                          'suggest_subagent_task_tool_event',
                          'mcp_tool_event',
                          'web_search_tool_event',
                          'image_generation_tool_event',
                          'tool_search_event'
                      )
                    """.trimIndent(),
                ).use { deleteLegacyStmt ->
                    db.prepare(
                        """
                        SELECT coalesce(max("index"), -1)
                        FROM agent_commits
                        WHERE agent_id = ?
                        """.trimIndent(),
                    ).use { maxIndexStmt ->
                        db.prepare(
                            """
                            SELECT 1
                            FROM agent_commits
                            WHERE agent_id = ? AND "index" = ?
                              AND type NOT IN (
                                  'settings', 'unstable', 'steer', 'ask_user',
                                  'token_count_legacy', 'token_count_initialization', 'token_count_compaction', 'token_count_response',
                                  'llm_request_meta_legacy', 'llm_request_meta_initialization', 'llm_request_meta_compaction', 'llm_request_meta_response'
                              )
                            LIMIT 1
                            """.trimIndent(),
                        ).use { hasCleanStmt ->
                            db.prepare(
                                "INSERT OR REPLACE INTO agent_commits VALUES (?, ?, ?, ?, ?)",
                            ).use { upsertStmt ->
                                for (agentId in agentIds) {
                                    currentCoroutineContext().ensureActive()
                                    val rawByKey = LinkedHashMap<Pair<Long, String>, CommitRecord>()
                                    var hasActionableCommit = false

                                    selectNonUnstableStmt.reset()
                                    selectNonUnstableStmt.bind(1, agentId)
                                    while (selectNonUnstableStmt.step()) {
                                        val cols = selectNonUnstableStmt.columns()
                                        val type = cols[0] as String
                                        val index = cols[1] as Long
                                        val payload = cols[2] as String
                                        val createdAt = cols[3] as String
                                        if (
                                            type in RetiredToolCommitTypes ||
                                            type == "context_compaction" ||
                                            (type == "invalid_tool_call" && "tool_search" in payload)
                                        ) {
                                            hasActionableCommit = true
                                        }
                                        rawByKey[index to type] = CommitRecord(agentId, type, index, payload, createdAt)
                                    }

                                    selectDirtyUnstableStmt.reset()
                                    selectDirtyUnstableStmt.bind(1, agentId)
                                    while (selectDirtyUnstableStmt.step()) {
                                        val cols = selectDirtyUnstableStmt.columns()
                                        val index = cols[0] as Long
                                        val payload = cols[1] as String
                                        val createdAt = cols[2] as String
                                        hasActionableCommit = true
                                        rawByKey[index to "unstable"] = CommitRecord(agentId, "unstable", index, payload, createdAt)
                                    }

                                    if (hasActionableCommit) {
                                        selectTailUnstableStmt.reset()
                                        selectTailUnstableStmt.bind(1, agentId)
                                        if (selectTailUnstableStmt.step()) {
                                            val cols = selectTailUnstableStmt.columns()
                                            val index = cols[0] as Long
                                            val payload = cols[1] as String
                                            val createdAt = cols[2] as String
                                            rawByKey.getOrPut(index to "unstable") {
                                                CommitRecord(agentId, "unstable", index, payload, createdAt)
                                            }
                                        }
                                    }

                                    // Always remove dropped/retired row types for this agent via PK (agent_id, type).
                                    deleteLegacyStmt.reset()
                                    deleteLegacyStmt.bind(1, agentId)
                                    deleteLegacyStmt.step()

                                    if (!hasActionableCommit) continue

                                    val sortedRawCommits = rawByKey.values.sortedWith(
                                        compareBy<CommitRecord> { it.index }.thenBy { it.type },
                                    )
                                    val survivingOriginals = HashMap<Pair<Long, String>, String>(sortedRawCommits.size)
                                    val legacyWorkIndicesRemovedIfMoved = HashSet<Int>()
                                    for (raw in sortedRawCommits) {
                                        if (raw.type !in RetiredToolCommitTypes) {
                                            survivingOriginals[raw.index to raw.type] = raw.payloadJson
                                        }
                                        if (raw.type == "request_user_input_tool_event") {
                                            legacyWorkIndicesRemovedIfMoved += raw.index.toInt()
                                        }
                                    }

                                    val maxIndex: Long by lazy(LazyThreadSafetyMode.NONE) {
                                        maxIndexStmt.reset()
                                        maxIndexStmt.bind(1, agentId)
                                        if (maxIndexStmt.step()) maxIndexStmt.columns()[0] as Long else -1L
                                    }

                                    val migratedCommits = rewriteLegacyCommitsForAgent(
                                        agentId = agentId,
                                        rawAgentCommits = sortedRawCommits,
                                        path = sourcePath,
                                        externalMaxIndex = maxIndex,
                                        externalHasCleanEventAtIndex = { idx ->
                                            hasCleanStmt.reset()
                                            hasCleanStmt.bind(1, agentId)
                                            hasCleanStmt.bind(2, idx)
                                            hasCleanStmt.step()
                                        },
                                    )

                                    val upgradedCommits = upgradeCompactionCommitsForAgent(migratedCommits, sourcePath)
                                    val delta = AgentMirrorDelta()
                                    val presentWorkIndices = HashSet<Int>()

                                    for (commit in upgradedCommits) {
                                        val commitType = runCatching { AgentCommitType.fromWireName(commit.type) }.getOrNull()
                                        if (commitType != null && commitType in AgentCommitType.WorkTypes) {
                                            presentWorkIndices += commit.index.toInt()
                                        }
                                        val originalPayload = survivingOriginals[commit.index to commit.type]
                                        if (originalPayload != null && originalPayload == commit.payloadJson) {
                                            continue
                                        }
                                        try {
                                            upsertStmt.reset()
                                            upsertStmt.bind(1, commit.agentId)
                                            upsertStmt.bind(2, commit.type)
                                            upsertStmt.bind(3, commit.index)
                                            upsertStmt.bind(4, commit.payloadJson)
                                            upsertStmt.bind(5, commit.createdAt)
                                            upsertStmt.step()
                                        } catch (failure: SqliteFailure) {
                                            throw IOException(
                                                "Invalid migration commit record: agent=${commit.agentId}, index=${commit.index}",
                                                failure,
                                            )
                                        }
                                        if (commitType != null) {
                                            val timeline = commitType.legacyTimelineName
                                            if (timeline in SyncedMirrorTimelines) {
                                                delta.putUpsert(
                                                    timeline,
                                                    commit.index.toInt(),
                                                    encodeMirrorPayload(commitType, commit.payloadJson),
                                                )
                                            }
                                        }
                                    }

                                    for (removedWorkIdx in legacyWorkIndicesRemovedIfMoved) {
                                        if (removedWorkIdx !in presentWorkIndices) {
                                            delta.putDelete("work", removedWorkIdx)
                                        }
                                    }

                                    if (!delta.isEmpty) {
                                        mirrorDeltas[agentId] = delta
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RetainedCommitWireNames: Set<String> =
    AgentCommitType.CompactionRetainedTypes.mapTo(mutableSetOf()) { it.wireName }

private fun upgradeCompactionCommitsForAgent(
    commits: List<CommitRecord>,
    path: Path,
): List<CommitRecord> {
    val hasAnyCompaction = commits.any { it.type == AgentCommitType.ContextCompaction.wireName }
    if (!hasAnyCompaction) return commits

    val upgraded = ArrayList<CommitRecord>(commits.size)
    var previousRetainedItems: List<CompactionRetainedItem> = emptyList()
    val incrementalRetained = mutableListOf<CompactionRetainedItem>()

    for (commit in commits) {
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
                previousRetainedItems = computedRetained
                incrementalRetained.clear()
            }

            else -> {
                upgraded += commit
            }
        }
    }
    return upgraded
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

private val SyncedMirrorTimelines: Set<String> = setOf("index", "work", "unstable", "ask-user")

private fun encodeMirrorPayload(commitType: AgentCommitType, payloadJson: String): String =
    when (commitType) {
        in AgentCommitType.IndexTypes -> {
            val decoded = commitType.decodePayloadJson(payloadJson, OpenAiJsonCodec) as CleanIndexEntry
            OpenAiJsonCodec.encodeToString(CleanIndexEntry.serializer(), decoded)
        }
        in AgentCommitType.WorkTypes -> {
            val decoded = commitType.decodePayloadJson(payloadJson, OpenAiJsonCodec) as StableWorkEvent
            OpenAiJsonCodec.encodeToString(StableWorkEvent.serializer(), decoded)
        }
        else -> payloadJson
    }

private suspend fun applyMirrorDeltas(
    home: Path,
    mirrorDeltas: Map<Long, AgentMirrorDelta>,
    fileSystem: CoroutineFileSystem,
) {
    if (mirrorDeltas.isEmpty()) return
    val sessionsRoot = Path(home, "sessions")
    if (!fileSystem.exists(sessionsRoot)) return
    for ((agentId, delta) in mirrorDeltas) {
        currentCoroutineContext().ensureActive()
        val sessionRoot = Path(sessionsRoot, agentId.toString())
        if (!fileSystem.exists(sessionRoot)) continue
        val touchedTimelines = delta.upsertsByTimeline.keys + delta.deletesByTimeline.keys
        for (timeline in touchedTimelines) {
            val dir = timelineDirectory(sessionRoot, timeline)
            val upserts = delta.upsertsByTimeline[timeline].orEmpty()
            val deletes = delta.deletesByTimeline[timeline].orEmpty()
            if (!fileSystem.exists(dir) && upserts.isEmpty()) continue
            fileSystem.createDirectories(dir)
            for (idx in deletes) {
                fileSystem.delete(recordPath(dir, idx), mustExist = false)
            }
            for ((idx, jsonText) in upserts) {
                fileSystem.writeString(recordPath(dir, idx), jsonText)
            }
            var maxIdx = -1
            for (child in fileSystem.list(dir)) {
                val name = child.name
                if (name == "latest.json" || !name.endsWith(".json")) continue
                val parsed = name.removeSuffix(".json").toIntOrNull() ?: continue
                if (parsed >= 0 && parsed > maxIdx) maxIdx = parsed
            }
            fileSystem.writeString(latestIndexPath(dir), maxIdx.toString())
        }
    }
}

private suspend fun reconcileMirrorsOnReentry(
    home: Path,
    target: Path,
    fileSystem: CoroutineFileSystem,
) {
    val sessionsRoot = Path(home, "sessions")
    if (!fileSystem.exists(sessionsRoot)) return
    openSqliteConnection(target, readOnly = true).use { db ->
        val agentIds = db.query("SELECT agent_id FROM agents ORDER BY agent_id").map { it[0] as Long }
        db.prepare(
            """
            SELECT type, "index", payload_json
            FROM agent_commits
            WHERE agent_id = ?
              AND type IN (
                  'read_media_tool_event',
                  'json_tool_event',
                  'text_tool_event',
                  'ask_user',
                  'event_ask_user',
                  'context_compaction',
                  'invalid_tool_call'
              )
            """.trimIndent(),
        ).use { selectStmt ->
            for (agentId in agentIds) {
                currentCoroutineContext().ensureActive()
                val sessionRoot = Path(sessionsRoot, agentId.toString())
                if (!fileSystem.exists(sessionRoot)) continue
                selectStmt.reset()
                selectStmt.bind(1, agentId)
                val delta = AgentMirrorDelta()
                while (selectStmt.step()) {
                    val cols = selectStmt.columns()
                    val wireType = cols[0] as String
                    val index = (cols[1] as Long).toInt()
                    val payloadJson = cols[2] as String
                    val commitType = runCatching { AgentCommitType.fromWireName(wireType) }.getOrNull() ?: continue
                    val timeline = commitType.legacyTimelineName
                    if (timeline in SyncedMirrorTimelines) {
                        delta.putUpsert(timeline, index, encodeMirrorPayload(commitType, payloadJson))
                        if (commitType == AgentCommitType.EventAskUser) {
                            delta.putDelete("work", index)
                        }
                    }
                }
                if (!delta.isEmpty) {
                    applyMirrorDeltas(home, mapOf(agentId to delta), fileSystem)
                }
            }
        }
    }
}

private suspend fun matchesPublishedV0_7_2(path: Path): Boolean {
    try {
        openSqliteConnection(path, readOnly = true).use { db ->
            verifyV0_7_2SchemaMetadata(db)
            verify(db.query("PRAGMA foreign_key_check").isEmpty())
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

private suspend fun verifyV0_7_2SchemaMetadata(db: SqliteConnection) {
    currentCoroutineContext().ensureActive()
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
            "'await_event_tool_event'" in tableSql &&
            "'read_media_tool_event'" in tableSql &&
            "'image_view_tool_event'" !in tableSql &&
            "'request_user_input_tool_event'" !in tableSql &&
            "'mcp_tool_event'" !in tableSql &&
            "'compaction_point'" !in tableSql,
    )
}
