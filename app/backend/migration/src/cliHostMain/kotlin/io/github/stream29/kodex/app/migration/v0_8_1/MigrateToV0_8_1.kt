package io.github.stream29.kodex.app.migration.v0_8_1

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

private const val HomeDatabaseFile = "kodex.db"
private const val StagingDatabaseFile = ".kodex.db.migrating-0.8.1"
private val StagingDatabaseFiles = listOf(
    StagingDatabaseFile,
    "$StagingDatabaseFile-journal",
    "$StagingDatabaseFile-wal",
    "$StagingDatabaseFile-shm",
)
private val SourceSidecarFiles = listOf(
    "$HomeDatabaseFile-journal",
    "$HomeDatabaseFile-wal",
    "$HomeDatabaseFile-shm",
)

private const val FrozenApplicationId = 0x4B445830L
private const val SourceSchemaVersion = 2L
private const val TargetSchemaVersion = 3L
private const val TargetPageSize = 16384L

// Frozen DDL statements for generation-1 schema v2 (agent_commits WITHOUT ROWID)
// and schema v3 (agent_commits standard ROWID table, 16 KiB page size).
private val FrozenCommonDdlBeforeAgentCommits = listOf(
    """
CREATE TABLE sessions (
    session_id INTEGER PRIMARY KEY CHECK (session_id BETWEEN 0 AND 2147483647)
) STRICT
    """.trim(),
    """
CREATE TABLE agents (
    agent_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (agent_id BETWEEN 0 AND 2147483647),
    session_id INTEGER NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    parent_agent_id INTEGER REFERENCES agents(agent_id) ON DELETE CASCADE,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1))
) STRICT
    """.trim(),
    "CREATE UNIQUE INDEX idx_agents_root_session ON agents(session_id) WHERE parent_agent_id IS NULL",
)

private val FrozenCommonDdlAfterAgentCommits = listOf(
    """CREATE INDEX commits_global_index ON agent_commits(agent_id, "index", type)""",
    """
CREATE INDEX commits_settings_cwd ON agent_commits(
    json_extract(payload_json, '$.cwd'), agent_id, "index" DESC
) WHERE type = 'settings'
    """.trim(),
    """
CREATE TABLE session_catalog_view (
    root_agent_id INTEGER PRIMARY KEY REFERENCES agents(agent_id) ON DELETE CASCADE,
    name TEXT,
    cwd TEXT,
    created_at TEXT,
    updated_at TEXT
) STRICT
    """.trim(),
    """
CREATE TABLE schedules (
    schedule_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (schedule_id >= 1),
    agent_id INTEGER NOT NULL CHECK (agent_id BETWEEN 0 AND 2147483647),
    trigger_at_epoch_seconds INTEGER NOT NULL,
    trigger_at_nanoseconds INTEGER NOT NULL CHECK (trigger_at_nanoseconds BETWEEN 0 AND 999999999),
    cron TEXT,
    description TEXT NOT NULL,
    created_at TEXT NOT NULL
) STRICT
    """.trim(),
    """
CREATE INDEX idx_schedules_trigger_at
    ON schedules(trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id)
    """.trim(),
    """
CREATE INDEX idx_schedules_agent_id
    ON schedules(agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id)
    """.trim(),
    """
CREATE TABLE fork_context_recovery (
    agent_id INTEGER NOT NULL,
    checkpoint_index INTEGER NOT NULL,
    commit_type TEXT NOT NULL DEFAULT 'context_compaction' CHECK (commit_type = 'context_compaction'),
    phase TEXT NOT NULL CHECK (phase IN ('Empty', 'UserMessage', 'AssistantMessage', 'ToolCompleted')),
    starts_new_turn INTEGER NOT NULL CHECK (starts_new_turn IN (0, 1)),
    PRIMARY KEY (agent_id, checkpoint_index),
    FOREIGN KEY (agent_id, commit_type, checkpoint_index)
        REFERENCES agent_commits(agent_id, type, "index") ON DELETE CASCADE
) STRICT, WITHOUT ROWID
    """.trim(),
    """
CREATE TABLE session_groups (
    cwd_key TEXT PRIMARY KEY,
    cwd TEXT UNIQUE,
    group_nonce INTEGER NOT NULL DEFAULT 1
) STRICT
    """.trim(),
    """
CREATE INDEX idx_session_catalog_view_cwd_updated
    ON session_catalog_view (cwd, updated_at DESC, root_agent_id DESC)
    """.trim(),
)

private fun agentCommitsDdl(withoutRowId: Boolean): String = buildString {
    append(
        """
CREATE TABLE agent_commits (
    agent_id INTEGER NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (
        type IN (
            'user_message',
            'event_ask_user',
            'event_schedule',
            'event_terminal_exited',
            'event_subagent_created',
            'event_subagent_completed',
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
            'llm_request_meta_legacy',
            'llm_request_meta_initialization',
            'llm_request_meta_compaction',
            'llm_request_meta_response',
            'unstable',
            'steer',
            'ask_user'
        )
    ),
    "index" INTEGER NOT NULL CHECK ("index" BETWEEN 0 AND 2147483647),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    created_at TEXT NOT NULL,
    PRIMARY KEY (agent_id, type, "index")
) STRICT
        """.trim(),
    )
    if (withoutRowId) append(", WITHOUT ROWID")
}

private val FrozenV2Ddl = FrozenCommonDdlBeforeAgentCommits + agentCommitsDdl(withoutRowId = true) + FrozenCommonDdlAfterAgentCommits
private val FrozenV3Ddl = FrozenCommonDdlBeforeAgentCommits + agentCommitsDdl(withoutRowId = false) + FrozenCommonDdlAfterAgentCommits

private val FrozenV2Schema = (FrozenV2Ddl + "CREATE TABLE sqlite_sequence(name,seq)").toSet()
private val FrozenV3Schema = (FrozenV3Ddl + "CREATE TABLE sqlite_sequence(name,seq)").toSet()

private val CopiedTables = listOf(
    "sessions" to "session_id",
    "agents" to "agent_id, session_id, parent_agent_id, completed",
    "agent_commits" to """agent_id, type, "index", payload_json, created_at""",
    "session_catalog_view" to "root_agent_id, name, cwd, created_at, updated_at",
    "session_groups" to "cwd_key, cwd, group_nonce",
    "schedules" to "schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at",
    "fork_context_recovery" to "agent_id, checkpoint_index, commit_type, phase, starts_new_turn",
)

internal suspend fun migrateToV0_8_1(home: Path, fileSystem: CoroutineFileSystem) {
    cleanupStagingFiles(home, fileSystem)
    val database = Path(home, HomeDatabaseFile)
    val staging = Path(home, StagingDatabaseFile)

    val alreadyMigrated = withContext(Dispatchers.IO) {
        openExistingSqliteConnection(database).use { source ->
            check(source.query("PRAGMA main.application_id") == listOf(listOf(FrozenApplicationId))) {
                "Unrecognized Kodex SQLite database signature during 0.8.1 migration."
            }
            val userVersion = source.query("PRAGMA main.user_version").singleOrNull()?.singleOrNull() as? Long
            val schema = source.schemaStatements()
            when {
                userVersion == TargetSchemaVersion && schema == FrozenV3Schema -> true
                userVersion == SourceSchemaVersion && schema == FrozenV2Schema -> {
                    // Checkpoint any committed WAL frames into kodex.db and remove WAL/SHM sidecars
                    // so staging reads a self-contained source and atomicMove never meets stale WAL files.
                    check(source.query("PRAGMA journal_mode=DELETE") == listOf(listOf("delete"))) {
                        "Failed to switch source database out of WAL mode before 0.8.1 migration."
                    }
                    false
                }
                else -> error(
                    "Unexpected Kodex SQLite database state for 0.8.1 migration (user_version=$userVersion).",
                )
            }
        }
    }
    if (alreadyMigrated) return

    check(SourceSidecarFiles.none { fileSystem.exists(Path(home, it)) }) {
        "Source SQLite sidecars remained after switching to DELETE journal mode before 0.8.1 migration."
    }

    try {
        withContext(Dispatchers.IO) {
            openSqliteConnection(staging).use { target ->
                check(target.query("PRAGMA journal_mode=DELETE") == listOf(listOf("delete"))) {
                    "Staging database for 0.8.1 migration must use DELETE journal mode."
                }
                target.execute("PRAGMA synchronous=FULL")
                target.execute("PRAGMA main.page_size=$TargetPageSize")
                if (target.query("PRAGMA main.page_size") != listOf(listOf(TargetPageSize))) {
                    target.execute("VACUUM")
                }
                target.execute("PRAGMA foreign_keys=OFF")

                target.transaction {
                    FrozenV3Ddl.forEach { target.execute(it) }
                    target.execute("PRAGMA main.user_version=$TargetSchemaVersion")
                    target.execute("PRAGMA main.application_id=$FrozenApplicationId")
                }

                target.execute("ATTACH DATABASE ? AS src", listOf(database.toString()))
                try {
                    target.transaction {
                        for ((table, columns) in CopiedTables) {
                            val orderBy = if (table == "agent_commits") """ ORDER BY agent_id, "index", type""" else ""
                            target.execute("INSERT INTO main.$table($columns) SELECT $columns FROM src.$table$orderBy")
                        }
                        target.execute("DELETE FROM main.sqlite_sequence")
                        target.execute("INSERT INTO main.sqlite_sequence(name, seq) SELECT name, seq FROM src.sqlite_sequence")
                    }

                    for ((table, _) in CopiedTables) {
                        val expected = target.query("SELECT count(*) FROM src.$table").single().single()
                        val actual = target.query("SELECT count(*) FROM main.$table").single().single()
                        check(actual == expected) {
                            "Row count mismatch while migrating $table to 0.8.1: expected $expected, got $actual."
                        }
                    }
                    val expectedSeq = target.query("SELECT name, seq FROM src.sqlite_sequence ORDER BY name")
                    val actualSeq = target.query("SELECT name, seq FROM main.sqlite_sequence ORDER BY name")
                    check(actualSeq == expectedSeq) {
                        "sqlite_sequence mismatch while migrating to 0.8.1: expected $expectedSeq, got $actualSeq."
                    }
                } finally {
                    target.execute("DETACH DATABASE src")
                }

                target.execute("PRAGMA foreign_keys=ON")
                check(target.query("PRAGMA main.foreign_key_check").isEmpty()) {
                    "Foreign key check failed on staged 0.8.1 database."
                }
                check(target.query("PRAGMA main.quick_check") == listOf(listOf("ok"))) {
                    "SQLite quick_check failed on staged 0.8.1 database."
                }
                check(target.query("PRAGMA main.page_size") == listOf(listOf(TargetPageSize))) {
                    "Staged 0.8.1 database did not adopt page_size=$TargetPageSize."
                }
                check(target.query("PRAGMA main.user_version") == listOf(listOf(TargetSchemaVersion))) {
                    "Staged 0.8.1 database has unexpected user_version."
                }
                check(target.query("PRAGMA main.application_id") == listOf(listOf(FrozenApplicationId))) {
                    "Staged 0.8.1 database has unexpected application_id."
                }
                check(target.schemaStatements() == FrozenV3Schema) {
                    "Staged 0.8.1 database schema does not match frozen v3 DDL."
                }
            }
        }

        check(StagingDatabaseFiles.drop(1).none { fileSystem.exists(Path(home, it)) }) {
            "SQLite left staging sidecars after closing staged 0.8.1 database."
        }
        check(SourceSidecarFiles.none { fileSystem.exists(Path(home, it)) }) {
            "Source SQLite sidecars reappeared before 0.8.1 atomic cutover."
        }
        fileSystem.atomicMove(staging, database)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            runCatching { cleanupStagingFiles(home, fileSystem) }
        }
        throw failure
    }
}

private suspend fun cleanupStagingFiles(home: Path, fileSystem: CoroutineFileSystem) {
    for (name in StagingDatabaseFiles) {
        fileSystem.delete(Path(home, name), mustExist = false)
    }
}

private fun SqliteConnection.schemaStatements(): Set<Any?> =
    query("SELECT sql FROM main.sqlite_schema WHERE sql IS NOT NULL").map { it.single() }.toSet()
