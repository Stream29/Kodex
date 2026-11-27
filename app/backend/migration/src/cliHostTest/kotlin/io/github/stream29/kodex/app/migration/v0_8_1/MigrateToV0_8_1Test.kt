package io.github.stream29.kodex.app.migration.v0_8_1

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.KodexHomeDatabaseApplicationId
import io.github.stream29.kodex.agentstorage.sqlite.KodexHomeDatabasePageSize
import io.github.stream29.kodex.agentstorage.sqlite.SqliteSchemaVersion
import io.github.stream29.kodex.app.migration.HomeDatabaseFile
import io.github.stream29.kodex.app.migration.HomeVersionFile
import io.github.stream29.kodex.app.migration.KodexHomeMigrations
import io.github.stream29.kodex.app.migration.MigrationVersion
import io.github.stream29.kodex.app.migration.deleteTestHome
import io.github.stream29.kodex.app.migration.homeTestFileSystem
import io.github.stream29.kodex.app.migration.newTestHome
import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.migration.readyMarker
import io.github.stream29.kodex.app.migration.writeFixtureCheckpoint
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val fs = homeTestFileSystem
private val v081 = MigrationVersion("0.8.1")
private val v090 = MigrationVersion("0.9.0")
private val v091 = MigrationVersion("0.9.1")

public val migrateToV0_8_1Test by testSuite {
    testFixture { newTestHome() } closeWith {
        deleteTestHome(this)
    } asParameterForEach {
        test("migrates generation-1 schema v2 WAL database through 0.8.1 to current schema preserving all data and user files") { home ->
            createFrozenV2Fixture(home, enableWal = true)
            fs.writeString(Path(home, "AGENTS.md"), "# User Instructions\n")
            fs.writeString(Path(home, "settings.yml"), "model: synthetic\n")

            val started = mutableListOf<Pair<MigrationVersion, MigrationVersion>>()
            val handle = prepareKodexHome(
                home = home,
                currentVersion = v091,
                migrations = KodexHomeMigrations,
                fileSystem = fs,
                onMigrationStarted = { from, to -> started += from to to },
            )
            try {
                assertEquals(listOf(MigrationVersion("0.8.0") to v081, v081 to v090, v090 to v091), started)
                assertEquals(v091, handle.version)
                assertEquals(readyMarker("0.9.1"), fs.readString(Path(home, HomeVersionFile)))
                assertEquals("# User Instructions\n", fs.readString(Path(home, "AGENTS.md")))
                assertEquals("model: synthetic\n", fs.readString(Path(home, "settings.yml")))
                assertFalse(fs.exists(Path(home, ".kodex.db.migrating-0.8.1")))
                assertFalse(fs.exists(Path(home, "$HomeDatabaseFile-wal")))
                assertFalse(fs.exists(Path(home, "$HomeDatabaseFile-shm")))
            } finally {
                handle.closeAndJoin()
            }

            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                assertEquals(SqliteSchemaVersion.toLong(), db.query("PRAGMA main.user_version").single().single())
                assertEquals(KodexHomeDatabaseApplicationId.toLong(), db.query("PRAGMA main.application_id").single().single())
                assertEquals(KodexHomeDatabasePageSize.toLong(), db.query("PRAGMA main.page_size").single().single())
                assertEquals(listOf(listOf(0L, 1L)), db.query("SELECT wr, strict FROM pragma_table_list WHERE schema = 'main' AND name = 'agent_commits'"))
                assertTrue(db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'fork_context_recovery'").isEmpty())
                assertTrue(db.query("PRAGMA main.foreign_key_check").isEmpty())
                assertEquals(listOf(listOf("ok")), db.query("PRAGMA main.integrity_check"))
                assertFixtureDataIntact(db, expectForkRecovery = false)
            }
        }

        test("cleans leftover staging files from an interrupted attempt and retries cleanly") { home ->
            createFrozenV2Fixture(home, enableWal = false)
            fs.writeString(Path(home, ".kodex.db.migrating-0.8.1"), "interrupted staging bytes")
            fs.writeString(Path(home, ".kodex.db.migrating-0.8.1-journal"), "interrupted journal")

            prepareKodexHome(
                home = home,
                currentVersion = v091,
                migrations = KodexHomeMigrations,
                fileSystem = fs,
            ).closeAndJoin()

            assertEquals(readyMarker("0.9.1"), fs.readString(Path(home, HomeVersionFile)))
            assertFalse(fs.exists(Path(home, ".kodex.db.migrating-0.8.1")))
            assertFalse(fs.exists(Path(home, ".kodex.db.migrating-0.8.1-journal")))
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                assertEquals(KodexHomeDatabasePageSize.toLong(), db.query("PRAGMA main.page_size").single().single())
                assertFixtureDataIntact(db, expectForkRecovery = false)
            }
        }

        test("converges when database was already swapped to v3 before checkpoint write") { home ->
            createFrozenV2Fixture(home, enableWal = false)
            migrateToV0_8_1(home, fs)
            // Simulate crash right before version.json checkpoint update: DB is v3, marker still 0.8.0.
            assertEquals(readyMarker("0.8.0"), fs.readString(Path(home, HomeVersionFile)))
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                assertEquals(3L, db.query("PRAGMA main.user_version").single().single())
                assertFixtureDataIntact(db, expectForkRecovery = true)
            }

            prepareKodexHome(
                home = home,
                currentVersion = v091,
                migrations = KodexHomeMigrations,
                fileSystem = fs,
            ).closeAndJoin()

            assertEquals(readyMarker("0.9.1"), fs.readString(Path(home, HomeVersionFile)))
            openExistingSqliteConnection(Path(home, HomeDatabaseFile)).use { db ->
                assertEquals(SqliteSchemaVersion.toLong(), db.query("PRAGMA main.user_version").single().single())
                assertFixtureDataIntact(db, expectForkRecovery = false)
            }
        }

        test("fails closed on unexpected schema without mutating database or advancing checkpoint") { home ->
            createFrozenV2Fixture(home, enableWal = false)
            val database = Path(home, HomeDatabaseFile)
            openExistingSqliteConnection(database).use { db ->
                db.execute("DROP TABLE session_groups")
            }
            val beforeBytes = fs.readBytes(database).toList()

            assertFailsWith<IllegalStateException> {
                prepareKodexHome(
                    home = home,
                    currentVersion = v081,
                    migrations = KodexHomeMigrations,
                    fileSystem = fs,
                ).closeAndJoin()
            }

            assertEquals(readyMarker("0.8.0"), fs.readString(Path(home, HomeVersionFile)))
            assertEquals(beforeBytes, fs.readBytes(database).toList())
            assertFalse(fs.exists(Path(home, ".kodex.db.migrating-0.8.1")))
        }
    }
}

private suspend fun createFrozenV2Fixture(home: Path, enableWal: Boolean) {
    val database = Path(home, HomeDatabaseFile)
    openSqliteConnection(database).use { db ->
        db.execute("PRAGMA main.page_size=4096")
        db.transaction {
            FrozenV2FixtureDdl.forEach { db.execute(it) }
            db.execute("PRAGMA main.user_version=2")
            db.execute("PRAGMA main.application_id=$KodexHomeDatabaseApplicationId")
        }
        if (enableWal) {
            assertEquals(listOf(listOf("wal")), db.query("PRAGMA journal_mode=WAL"))
        }
        db.transaction {
            db.execute("INSERT INTO sessions(session_id) VALUES(0)")
            db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id, completed) VALUES(1, 0, NULL, 0)")
            db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id, completed) VALUES(2, 0, 1, 1)")
            // Create and delete a higher agent_id so sqlite_sequence watermark (5) exceeds max remaining agent_id (2).
            db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id, completed) VALUES(5, 0, 1, 0)")
            db.execute("DELETE FROM agents WHERE agent_id = 5")

            db.execute(
                """INSERT INTO agent_commits(agent_id, type, "index", payload_json, created_at) VALUES(1, 'settings', 0, '{"model":"synthetic","cwd":"/work"}', '2026-11-24T00:00:00Z')""",
            )
            db.execute(
                """INSERT INTO agent_commits(agent_id, type, "index", payload_json, created_at) VALUES(1, 'context_compaction', 1, '{"type":"context_compaction","items":[]}', '2026-11-24T00:01:00Z')""",
            )
            db.execute(
                """INSERT INTO agent_commits(agent_id, type, "index", payload_json, created_at) VALUES(2, 'user_message', 0, '{"type":"user_message","text":"hi"}', '2026-11-24T00:02:00Z')""",
            )
            db.execute(
                "INSERT INTO session_catalog_view(root_agent_id, name, cwd, created_at, updated_at) VALUES(1, 'Main', '/work', '2026-11-24T00:00:00Z', '2026-11-24T00:01:00Z')",
            )
            db.execute("INSERT INTO session_groups(cwd_key, cwd, group_nonce) VALUES('/work', '/work', 7)")
            db.execute(
                "INSERT INTO schedules(schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at) VALUES(3, 1, 1800000000, 123, '0 * * * *', 'hourly', '2026-11-24T00:00:00Z')",
            )
            db.execute(
                "INSERT INTO fork_context_recovery(agent_id, checkpoint_index, commit_type, phase, starts_new_turn) VALUES(1, 1, 'context_compaction', 'ToolCompleted', 1)",
            )
        }
    }
    writeFixtureCheckpoint(home, "0.8.0")
}

private fun assertFixtureDataIntact(db: SqliteConnection, expectForkRecovery: Boolean = true) {
    assertEquals(listOf(listOf(0L)), db.query("SELECT session_id FROM sessions"))
    assertEquals(
        listOf(listOf(1L, 0L, null, 0L), listOf(2L, 0L, 1L, 1L)),
        db.query("SELECT agent_id, session_id, parent_agent_id, completed FROM agents ORDER BY agent_id"),
    )
    assertEquals(
        listOf(
            listOf(1L, "settings", 0L, """{"model":"synthetic","cwd":"/work"}""", "2026-11-24T00:00:00Z"),
            listOf(1L, "context_compaction", 1L, """{"type":"context_compaction","items":[]}""", "2026-11-24T00:01:00Z"),
            listOf(2L, "user_message", 0L, """{"type":"user_message","text":"hi"}""", "2026-11-24T00:02:00Z"),
        ),
        db.query("""SELECT agent_id, type, "index", payload_json, created_at FROM agent_commits ORDER BY agent_id, "index", type"""),
    )
    assertEquals(
        listOf(listOf(1L, "Main", "/work", "2026-11-24T00:00:00Z", "2026-11-24T00:01:00Z")),
        db.query("SELECT root_agent_id, name, cwd, created_at, updated_at FROM session_catalog_view"),
    )
    assertEquals(
        listOf(listOf("/work", "/work", 7L)),
        db.query("SELECT cwd_key, cwd, group_nonce FROM session_groups"),
    )
    assertEquals(
        listOf(listOf(3L, 1L, 1800000000L, 123L, "0 * * * *", "hourly", "2026-11-24T00:00:00Z")),
        db.query("SELECT schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at FROM schedules"),
    )
    if (expectForkRecovery) {
        assertEquals(
            listOf(listOf(1L, 1L, "context_compaction", "ToolCompleted", 1L)),
            db.query("SELECT agent_id, checkpoint_index, commit_type, phase, starts_new_turn FROM fork_context_recovery"),
        )
    }
    assertEquals(
        listOf(listOf("agents", 5L), listOf("schedules", 3L)),
        db.query("SELECT name, seq FROM sqlite_sequence ORDER BY name"),
    )
}

private val FrozenV2FixtureDdl = listOf(
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
) STRICT, WITHOUT ROWID
    """.trim(),
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
