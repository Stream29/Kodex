package io.github.stream29.kodex.app.migration.v0_7_0

import io.github.stream29.kodex.agentstorage.filesystemlayout.readLatestIndex
import io.github.stream29.kodex.agentstorage.filesystemlayout.readRecord
import io.github.stream29.kodex.agentstorage.filesystemlayout.storedRecordIndexes
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
import kotlinx.serialization.json.*
import kotlin.time.Instant

internal suspend fun migrateToV0_7_0(home: Path, fileSystem: CoroutineFileSystem): Unit = withContext(Dispatchers.IO) {
    val staging = Path(home, StagingName)
    val target = Path(home, "kodex.db")
    CoroutineCloseable { cleanStaging(home, fileSystem) }.use {
        cleanStaging(home, fileSystem)
        val manifest = scanSessions(home, fileSystem)
        if (fileSystem.exists(target) && matchesPublished(target, manifest, fileSystem)) return@use
        fileSystem.createDirectories(home)
        openSqliteConnection(staging).use { connection ->
            initializeV0_7_0Schema(connection)
            importSessions(connection, manifest, fileSystem)
            verifyManifest(connection, manifest, fileSystem)
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
    }
}

private const val StagingName = ".kodex-migration-0.7.0-staging.db"
private val DirectIndexCommitTypes = setOf(
    "user_message",
    "developer_message",
    "agent_message",
    "request_user_input_tool_event",
    "suggest_subagent_task_tool_event",
    "plan_update",
    "compaction_point",
)
private val IndexCommitTypes = listOf(
    "user_message",
    "assistant_message",
    "assistant_message_commentary",
    "assistant_message_final",
    "developer_message",
    "agent_message",
    "request_user_input_tool_event",
    "suggest_subagent_task_tool_event",
    "plan_update",
    "compaction_point",
)
private val WorkCommitTypes = listOf(
    "reasoning",
    "context_compaction",
    "command_execution_tool_event",
    "patch_tool_event",
    "mcp_tool_event",
    "web_search_tool_event",
    "image_generation_tool_event",
    "image_view_tool_event",
    "tool_search_event",
    "invalid_tool_call",
    "server_tool_search",
    "hosted_web_search",
    "hosted_image_generation",
    "json_tool_event",
    "text_tool_event",
    "custom_tool_event",
)
private val TokenCountCommitTypes = listOf(
    "token_count_legacy",
    "token_count_initialization",
    "token_count_compaction",
    "token_count_response",
)
private val TimelineTypes = linkedMapOf(
    "index" to IndexCommitTypes,
    "work" to WorkCommitTypes,
    "settings" to listOf("settings"),
    "timestamp" to listOf("timestamp"),
    "token-count" to TokenCountCommitTypes,
    "unstable" to listOf("unstable"),
)
private val MigrationJson = Json

private fun initializeV0_7_0Schema(connection: SqliteConnection) {
    connection.transaction {
        v0_7_0Schema.split(';').filter { it.isNotBlank() }.forEach { connection.execute(it) }
        connection.execute("PRAGMA user_version=1")
    }
}

// language=SQL
private val v0_7_0Schema: String = """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1))
) STRICT;

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
            'token_count_legacy',
            'token_count_initialization',
            'token_count_compaction',
            'token_count_response',
            'unstable'
        )
    ),
    "index" INTEGER NOT NULL CHECK ("index" BETWEEN 0 AND 2147483647),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
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
    json_extract(ct.payload_json, '$'),
    json_extract(ut.payload_json, '$')
FROM sessions AS s
LEFT JOIN agent_commits AS st
    ON st.agent_id = s.session_id AND st.type = 'settings'
    AND st."index" = (
        SELECT max("index") FROM agent_commits
        WHERE agent_id = s.session_id AND type = 'settings'
    )
LEFT JOIN agent_commits AS ct
    ON ct.agent_id = s.session_id AND ct.type = 'timestamp'
    AND ct."index" = 0
LEFT JOIN agent_commits AS ut
    ON ut.agent_id = s.session_id AND ut.type = 'timestamp'
    AND ut."index" = (
        SELECT max("index") FROM agent_commits
        WHERE agent_id = s.session_id AND type = 'timestamp'
    );
""".trimIndent()

private data class SessionManifest(
    val index: Int,
    val directory: Path,
    val archived: Long,
    val indexes: Map<String, IntArray>,
)

private suspend fun cleanStaging(home: Path, fs: CoroutineFileSystem, sidecarsOnly: Boolean = false) {
    var failure: Throwable? = null
    for (suffix in if (sidecarsOnly) listOf("-wal", "-shm") else listOf("-wal", "-shm", "")) {
        try { fs.delete(Path(home, StagingName + suffix), mustExist = false) } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
        }
    }
    failure?.let { throw it }
}

private suspend fun scanSessions(home: Path, fs: CoroutineFileSystem): List<SessionManifest> {
    val sessions = Path(home, "sessions")
    val metadata = fs.metadataOrNull(sessions) ?: return emptyList()
    if (!metadata.isDirectory) throw IOException("Sessions path is not a directory: $sessions")
    return fs.list(sessions).filterNot { it.name.startsWith('.') }.map { entry ->
        currentCoroutineContext().ensureActive()
        val index = entry.name.toIntOrNull()
        if (index == null || index < 0 || index.toString() != entry.name ||
            fs.metadataOrNull(entry)?.isDirectory != true
        ) throw IOException("Invalid session entry: $entry")
        val directory = fs.resolve(entry)
        val indexes = TimelineTypes.mapValues { (fileName, _) ->
            val path = Path(directory, fileName)
            if (fs.metadataOrNull(path)?.isDirectory != true) throw IOException("Missing timeline directory: $path")
            val stored = storedRecordIndexes(path, fs)
            // The layout helper accepts integer spellings that are not JSON numbers.
            val latest = MigrationJson.parseLatest(fs.readString(Path(path, "latest.json")))
            if (readLatestIndex(path, fs) != (stored.lastOrNull() ?: -1) || latest != (stored.lastOrNull() ?: -1)) {
                throw IOException("Timeline latest pointer does not match records: $path")
            }
            for (stateIndex in setOfNotNull(stored.firstOrNull(), stored.lastOrNull())) {
                readBoundaryPayload(path, fileName, stateIndex, fs)
            }
            stored
        }
        SessionManifest(index, directory, if (fs.exists(Path(directory, "archive.mark"))) 1L else 0L, indexes)
    }.sortedBy { it.index }
}

private fun Json.parseLatest(raw: String): Int = try {
    val value = parseToJsonElement(raw) as? JsonPrimitive
    if (value == null || value.isString || value.int < -1 || value.content != value.int.toString()) {
        throw IllegalArgumentException()
    }
    value.int
} catch (_: IllegalArgumentException) {
    throw IOException("Invalid migration timeline latest pointer")
}

private suspend fun readPayload(path: Path, stateIndex: Int, fs: CoroutineFileSystem): String {
    currentCoroutineContext().ensureActive()
    try {
        return readRecord(path, stateIndex, fs).decodeToString(throwOnInvalidSequence = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw IOException("Invalid migration record: $path/$stateIndex.json")
    }
}

private fun resolveCommitType(path: Path, fileName: String, stateIndex: Int, raw: String): String {
    try {
        val json = MigrationJson.parseToJsonElement(raw)
        return when (fileName) {
            "timestamp" -> {
                val primitive = json as? JsonPrimitive ?: throw IllegalArgumentException()
                require(primitive.isString)
                Instant.parse(primitive.content)
                "timestamp"
            }
            "unstable" -> {
                json.jsonArray
                "unstable"
            }
            "settings" -> {
                json.jsonObject
                "settings"
            }
            "token-count" -> {
                val obj = json.jsonObject
                when ((obj["type"] ?: obj["kind"])?.jsonPrimitive?.takeIf { it.isString }?.content) {
                    "legacy", "token_count_legacy" -> "token_count_legacy"
                    "initialization", "token_count_initialization" -> "token_count_initialization"
                    "compaction", "token_count_compaction" -> "token_count_compaction"
                    "response", "token_count_response" -> "token_count_response"
                    else -> throw IllegalArgumentException()
                }
            }
            "index" -> {
                val obj = json.jsonObject
                when (val type = obj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content) {
                    "assistant_message" -> when (val phase = obj["phase"]) {
                        null, JsonNull -> "assistant_message"
                        is JsonPrimitive -> when (phase.takeIf { it.isString }?.content) {
                            "commentary" -> "assistant_message_commentary"
                            "final_answer" -> "assistant_message_final"
                            else -> throw IllegalArgumentException()
                        }
                        else -> throw IllegalArgumentException()
                    }
                    in DirectIndexCommitTypes -> type!!
                    else -> throw IllegalArgumentException()
                }
            }
            "work" -> {
                val obj = json.jsonObject
                val type = obj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content
                if (type != null && type in WorkCommitTypes) type else throw IllegalArgumentException()
            }
            else -> throw IllegalArgumentException()
        }
    } catch (_: Exception) {
        throw IOException("Invalid migration record: $path/$stateIndex.json")
    }
}

private suspend fun readBoundaryPayload(path: Path, fileName: String, stateIndex: Int, fs: CoroutineFileSystem): String {
    val raw = readPayload(path, stateIndex, fs)
    resolveCommitType(path, fileName, stateIndex, raw)
    return raw
}

private suspend fun importSessions(db: SqliteConnection, sessions: List<SessionManifest>, fs: CoroutineFileSystem) {
    // This private staging connection has one owner. Stream records inside one import transaction
    // rather than retaining decoded histories or payloads in memory.
    db.execute("BEGIN IMMEDIATE")
    try {
        for (session in sessions) {
            currentCoroutineContext().ensureActive()
            val id = session.index.toLong()
            db.execute("INSERT INTO sessions VALUES (?, ?)", listOf(id, session.archived))
            db.execute("INSERT INTO agents VALUES (?, ?, NULL)", listOf(id, id))
            for ((fileName, _) in TimelineTypes) {
                val timelinePath = Path(session.directory, fileName)
                for (index in session.indexes.getValue(fileName)) {
                    val payload = readPayload(timelinePath, index, fs)
                    val type = resolveCommitType(timelinePath, fileName, index, payload)
                    try {
                        db.execute("INSERT INTO agent_commits VALUES (?, ?, ?, ?)", listOf(id, type, index.toLong(), payload))
                    } catch (failure: SqliteFailure) {
                        throw IOException("Invalid migration record: $timelinePath/$index.json", failure)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        db.execute("COMMIT")
    } catch (primary: Throwable) {
        try { db.execute("ROLLBACK") } catch (rollback: Throwable) { primary.addSuppressed(rollback) }
        throw primary
    }
}

private suspend fun matchesPublished(path: Path, sessions: List<SessionManifest>, fs: CoroutineFileSystem): Boolean {
    try {
        openSqliteConnection(path, readOnly = true).use { db ->
            verifyManifest(db, sessions, fs)
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

private fun verify(condition: Boolean) {
    if (!condition) throw IOException("SQLite migration manifest mismatch")
}

private suspend fun verifyManifest(db: SqliteConnection, sessions: List<SessionManifest>, fs: CoroutineFileSystem) {
    currentCoroutineContext().ensureActive()
    verify(db.query("PRAGMA integrity_check") == listOf(listOf("ok")))
    verify(db.query("PRAGMA foreign_key_check").isEmpty())
    verify(db.query("PRAGMA user_version") == listOf(listOf(1L)))
    verify(db.query("SELECT session_id, archived FROM sessions ORDER BY session_id") ==
        sessions.map { listOf(it.index.toLong(), it.archived) })
    verify(db.query("SELECT count(*) FROM agents").single().single() == sessions.size.toLong())
    val expectedRecords = sessions.sumOf { session -> session.indexes.values.sumOf { it.size.toLong() } }
    verify(db.query("SELECT count(*) FROM agent_commits").single().single() == expectedRecords)
    verify(db.query("SELECT session_id FROM session_catalog ORDER BY session_id") ==
        sessions.map { listOf(it.index.toLong()) })
    for (session in sessions) {
        currentCoroutineContext().ensureActive()
        val id = session.index.toLong()
        verify(db.query("SELECT agent_id, session_id, parent_agent_id FROM agents WHERE agent_id=?", listOf(id)) ==
            listOf(listOf(id, id, null)))
        for ((fileName, types) in TimelineTypes) {
            val indexes = session.indexes.getValue(fileName)
            val expectedSum = if (indexes.isEmpty()) null else indexes.sumOf { it.toLong() }
            val typeClause = if (types.size == 1) "type=?" else "type IN (${types.joinToString(",") { "?" }})"
            verify(
                db.query(
                    """SELECT count(*), count(DISTINCT "index"), min("index"), max("index"), sum("index") FROM agent_commits WHERE agent_id=? AND $typeClause""",
                    listOf(id) + types,
                ) == listOf(listOf(indexes.size.toLong(), indexes.size.toLong(), indexes.firstOrNull()?.toLong(), indexes.lastOrNull()?.toLong(), expectedSum)),
            )
            val timelinePath = Path(session.directory, fileName)
            for (index in setOfNotNull(indexes.firstOrNull(), indexes.lastOrNull())) {
                val source = readPayload(timelinePath, index, fs)
                val expectedType = resolveCommitType(timelinePath, fileName, index, source)
                verify(db.query("""SELECT payload_json FROM agent_commits WHERE agent_id=? AND type=? AND "index"=?""", listOf(id, expectedType, index.toLong())) ==
                    listOf(listOf(source)))
            }
        }
        val settingsIndex = session.indexes.getValue("settings").lastOrNull()
        val settings = settingsIndex?.let {
            val path = Path(session.directory, "settings")
            try {
                MigrationJson.parseToJsonElement(readPayload(path, it, fs)).jsonObject
            } catch (_: Exception) {
                throw IOException("Invalid migration record: $path/$it.json")
            }
        }
        suspend fun timestamp(index: Int?): String? = index?.let {
            val path = Path(session.directory, "timestamp")
            try {
                val primitive = MigrationJson.parseToJsonElement(readPayload(path, it, fs)).jsonPrimitive
                require(primitive.isString)
                Instant.parse(primitive.content)
                primitive.content
            } catch (_: Exception) {
                throw IOException("Invalid migration record: $path/$it.json")
            }
        }
        val timestampIndexes = session.indexes.getValue("timestamp")
        val created = timestamp(0.takeIf { timestampIndexes.firstOrNull() == 0 })
        val updated = timestamp(timestampIndexes.lastOrNull())
        val name = if (settings == null) null else settings["threadName"]?.jsonPrimitive?.contentOrNull ?: ""
        val cwd = if (settings == null) null else settings["cwd"]?.jsonPrimitive?.contentOrNull ?: "."
        verify(db.query("SELECT * FROM session_catalog WHERE session_id=?", listOf(id)) ==
            listOf(listOf(id, session.archived, name, cwd, created, updated)))
    }
}
