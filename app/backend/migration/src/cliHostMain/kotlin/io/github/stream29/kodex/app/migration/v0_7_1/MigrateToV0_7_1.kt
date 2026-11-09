package io.github.stream29.kodex.app.migration.v0_7_1

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
import kotlin.time.Instant

internal suspend fun migrateToV0_7_1(home: Path, fileSystem: CoroutineFileSystem): Unit = withContext(Dispatchers.IO) {
    val staging = Path(home, StagingName)
    val target = Path(home, "kodex.db")
    CoroutineCloseable { cleanStaging(home, fileSystem) }.use {
        cleanStaging(home, fileSystem)
        if (fileSystem.exists(target) && matchesPublishedV0_7_1(target)) return@use
        fileSystem.createDirectories(home)
        val sourceSnapshot = if (fileSystem.exists(target)) readSourceDatabase(target) else SourceDatabaseSnapshot.Empty
        openSqliteConnection(staging).use { connection ->
            initializeV0_7_1Schema(connection)
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
    }
}

private const val StagingName = ".kodex-migration-0.7.1-staging.db"
private const val FallbackTimestamp = "1970-01-01T00:00:00Z"

private data class CommitRecord(
    val agentId: Long,
    val type: String,
    val index: Long,
    val payloadJson: String,
    val createdAt: String,
)

private data class SourceDatabaseSnapshot(
    val sessions: List<Pair<Long, Long>>,
    val agents: List<Triple<Long, Long, Long?>>,
    val commits: List<CommitRecord>,
) {
    companion object {
        val Empty = SourceDatabaseSnapshot(emptyList(), emptyList(), emptyList())
    }
}

private fun initializeV0_7_1Schema(connection: SqliteConnection) {
    connection.transaction {
        v0_7_1Schema.split(';').filter { it.isNotBlank() }.forEach { connection.execute(it) }
        connection.execute("PRAGMA user_version=1")
    }
}

// language=SQL
private val v0_7_1Schema: String = """
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
            'assistant_message',
            'assistant_message_commentary',
            'assistant_message_final',
            'developer_message',
            'agent_message',
            'request_user_input_tool_event',
            'suggest_subagent_task_tool_event',
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
            'unstable'
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

private fun verify(condition: Boolean, message: String = "SQLite 0.7.1 migration verification failed") {
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
            val columns = db.query("PRAGMA table_info(agent_commits)").mapNotNull { it.getOrNull(1) as? String }.toSet()
            val commits = if ("created_at" in columns) {
                db.query(
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
            } else {
                db.query(
                    """
                    SELECT
                        c.agent_id,
                        c.type,
                        c."index",
                        c.payload_json,
                        coalesce(
                            (
                                SELECT json_extract(t.payload_json, '$')
                                FROM agent_commits AS t
                                WHERE t.agent_id = c.agent_id
                                  AND t.type = 'timestamp'
                                  AND t."index" = c."index"
                            ),
                            (
                                SELECT json_extract(t.payload_json, '$')
                                FROM agent_commits AS t
                                WHERE t.agent_id = c.agent_id
                                  AND t.type = 'timestamp'
                                  AND t."index" <= c."index"
                                ORDER BY t."index" DESC
                                LIMIT 1
                            ),
                            (
                                SELECT json_extract(t.payload_json, '$')
                                FROM agent_commits AS t
                                WHERE t.agent_id = c.agent_id
                                  AND t.type = 'timestamp'
                                ORDER BY t."index" ASC
                                LIMIT 1
                            ),
                            '$FallbackTimestamp'
                        )
                    FROM agent_commits AS c
                    WHERE c.type NOT IN ('timestamp', 'compaction_point')
                    ORDER BY c.agent_id, c."index", c.type
                    """.trimIndent(),
                ).map { row ->
                    val createdAt = row[4] as? String ?: throw IOException("Invalid timestamp in $path")
                    validateTimestamp(createdAt, path)
                    CommitRecord(
                        agentId = row[0] as Long,
                        type = row[1] as String,
                        index = row[2] as Long,
                        payloadJson = row[3] as String,
                        createdAt = createdAt,
                    )
                }
            }
            SourceDatabaseSnapshot(sessions = sessions, agents = agents, commits = commits)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        throw io
    } catch (failure: SqliteFailure) {
        throw IOException("Invalid SQLite database at $path", failure)
    }
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

private suspend fun matchesPublishedV0_7_1(path: Path): Boolean {
    try {
        openSqliteConnection(path, readOnly = true).use { db ->
            verifyV0_7_1Structure(db)
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

private suspend fun verifyV0_7_1Structure(db: SqliteConnection) {
    currentCoroutineContext().ensureActive()
    verify(db.query("PRAGMA integrity_check") == listOf(listOf("ok")))
    verify(db.query("PRAGMA foreign_key_check").isEmpty())
    verify(db.query("PRAGMA user_version") == listOf(listOf(1L)))
    for ((name, type) in listOf(
        "sessions" to "table",
        "agents" to "table",
        "agent_commits" to "table",
        "idx_sessions_archived" to "index",
        "commits_global_index" to "index",
        "commits_settings_cwd" to "index",
        "session_catalog" to "view",
    )) {
        verify(db.query("SELECT 1 FROM sqlite_master WHERE name = ? AND type = ?", listOf(name, type)).size == 1)
    }
    val createdAtColumn = db.query("PRAGMA table_info(agent_commits)").firstOrNull { it.getOrNull(1) == "created_at" }
    verify(createdAtColumn != null && createdAtColumn[2] == "TEXT" && createdAtColumn[3] == 1L)
    val tableSql = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'agent_commits'")
        .singleOrNull()?.singleOrNull() as? String ?: ""
    verify("llm_request_meta_response" in tableSql)
    val viewSql = db.query("SELECT sql FROM sqlite_master WHERE type = 'view' AND name = 'session_catalog'")
        .singleOrNull()?.singleOrNull() as? String ?: ""
    verify("c.created_at" in viewSql)
    verify(db.query("SELECT count(*) FROM agent_commits WHERE type IN ('timestamp', 'compaction_point')") == listOf(listOf(0L)))
}

private suspend fun verifyStaging(db: SqliteConnection, snapshot: SourceDatabaseSnapshot) {
    verifyV0_7_1Structure(db)
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
