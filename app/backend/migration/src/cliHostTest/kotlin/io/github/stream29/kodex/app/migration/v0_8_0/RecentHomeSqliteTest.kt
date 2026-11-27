package io.github.stream29.kodex.app.migration.v0_8_0

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.requireEmptyKodexHomeDatabase
import io.github.stream29.kodex.app.migration.RecentUpgradeSchemaVersion
import io.github.stream29.kodex.app.migration.deleteTestHome
import io.github.stream29.kodex.app.migration.homeTestFileSystem
import io.github.stream29.kodex.app.migration.newTestHome
import io.github.stream29.kodex.app.migration.requireFrozenHomeSchema
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openExistingSqliteConnection
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import kotlinx.io.files.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

public val recentHomeSqliteTest by testSuite {
    test("source validation is read-only and refuses unknown, incomplete, or already-current schemas") {
        openInMemorySqliteConnection().use { source ->
            source.installReleasedSchema()
            val before = source.schemaDump()
            source.execute("PRAGMA query_only=ON")
            requireRecentHomeSourceSchema(source)
            assertEquals(before, source.schemaDump())
            source.execute("PRAGMA query_only=OFF")
            source.execute("CREATE TABLE unrelated(payload TEXT)")
            source.execute("INSERT INTO unrelated VALUES('leave alone')")
            assertFailsWith<IllegalStateException> { requireRecentHomeSourceSchema(source) }
            assertEquals(listOf(listOf("leave alone")), source.query("SELECT * FROM unrelated"))
            source.execute("DROP TABLE unrelated")
            source.execute("PRAGMA application_id=1")
            assertFailsWith<IllegalStateException> { requireRecentHomeSourceSchema(source) }
            source.execute("PRAGMA application_id=0")
            source.execute("PRAGMA user_version=3")
            assertFailsWith<IllegalStateException> { requireRecentHomeSourceSchema(source) }
            source.execute("PRAGMA user_version=1")
            source.execute("DROP TABLE schedules")
            assertFailsWith<IllegalStateException> { requireRecentHomeSourceSchema(source) }
        }
        openInMemorySqliteConnection().use { current ->
            initializeKodexHomeDatabase(current)
            assertFailsWith<IllegalStateException> { requireRecentHomeSourceSchema(current) }
            requireEmptyKodexHomeDatabase(current)
        }
    }

    testFixture { newTestHome() } closeWith { deleteTestHome(this) } asParameterForEach {
        test("copies identities, history keys, schedules, and high-water marks into the frozen 0.8.0 schema") { directory ->
            val source = Path(directory, "private ' snapshot.db")
            val target = Path(directory, "staging.db")
            source.newStandaloneDatabase { old ->
                old.installReleasedSchema()
                old.execute("INSERT INTO sessions VALUES (10, 1), (20, 0), (30, 0), (40, 0)")
                old.execute("INSERT INTO agents VALUES (91, 10, NULL, 0), (90, 20, NULL, 1), (93, 30, NULL, 0), (94, 40, NULL, 0)")
                old.execute("INSERT INTO agents VALUES (5, 10, 91, 1), (800, 10, 5, 0)")
                old.execute("DELETE FROM agents WHERE agent_id=800")
                old.insertCommit(91L, "settings", 0L, """{"threadName":"old","cwd":"/shared"}""", FirstTime)
                old.insertCommit(91L, "settings", 5L, """{"threadName":"latest","cwd":"/shared"}""", BaseTime)
                old.insertCommit(91L, "llm_request_meta_response", 9L, Response, ResponseTime)
                old.insertCommit(90L, "settings", 0L, """{"threadName":"same group","cwd":"/shared"}""", FirstTime)
                old.insertCommit(94L, "settings", 0L, """{"threadName":"default group"}""", FirstTime)
                old.insertCommit(5L, "assistant_message", 0L, Untouched, FirstTime)
                // The old schema permits schedules for IDs that no longer exist.
                old.execute("INSERT INTO schedules VALUES (7, 800, -42, 123456789, NULL, 'once', '$FirstTime')")
                old.execute("INSERT INTO schedules VALUES (8, 91, 42, 0, '* * * * *', 'repeat', '$FirstTime')")
                old.execute("INSERT INTO schedules VALUES (600, 91, 1, 0, NULL, 'removed', '$FirstTime')")
                old.execute("DELETE FROM schedules WHERE schedule_id=600")
                requireRecentHomeSourceSchema(old)
            }
            val before = homeTestFileSystem.readBytes(source)
            val (commitKeys, schedules, sequences) = openExistingSqliteConnection(source).use { old ->
                Triple(old.commitKeys(), old.query("SELECT * FROM schedules ORDER BY schedule_id"), old.sequences())
            }
            target.newStandaloneDatabase { db ->
                upgradeRecentHomeDatabase(db, source)
                db.requireFrozenHomeSchema(RecentUpgradeSchemaVersion)
                assertEquals(listOf(listOf(1L)), db.query("PRAGMA foreign_keys"))
                assertEquals(emptyList(), db.query("PRAGMA foreign_key_check"))
                assertEquals(listOf(listOf("ok")), db.query("PRAGMA integrity_check"))
                assertEquals(listOf(listOf(10L), listOf(20L), listOf(30L), listOf(40L)), db.query("SELECT * FROM sessions ORDER BY session_id"))
                assertEquals(
                    listOf(listOf(5L, 10L, 91L, 1L), listOf(90L, 20L, null, 0L), listOf(91L, 10L, null, 1L),
                        listOf(93L, 30L, null, 0L), listOf(94L, 40L, null, 0L)),
                    db.query("SELECT * FROM agents ORDER BY agent_id"),
                )
                assertEquals(commitKeys, db.commitKeys())
                assertEquals(listOf(listOf(Untouched)), db.query("SELECT payload_json FROM agent_commits WHERE agent_id=5"))
                assertEquals(
                    listOf(listOf(250_000_000L, 15L)),
                    db.query("""SELECT json_extract(payload_json, '$.elapsed_ns'),
                        json_extract(payload_json, '$.output_tokens')
                        FROM agent_commits WHERE type='llm_request_meta_response'"""),
                )
                assertEquals(
                    listOf(listOf(90L, "same group", "/shared", FirstTime, FixedFirstTime),
                        listOf(91L, "latest", "/shared", FirstTime, "2026-10-01T00:00:00.750000000Z"),
                        listOf(93L, null, null, null, null),
                        listOf(94L, "default group", ".", FirstTime, FixedFirstTime)),
                    db.query("SELECT * FROM session_catalog_view ORDER BY root_agent_id"),
                )
                assertEquals(
                    listOf(listOf("", null, 1L), listOf(".", ".", 1L), listOf("/shared", "/shared", 1L)),
                    db.query("SELECT * FROM session_groups ORDER BY cwd_key"),
                )
                assertEquals(schedules, db.query("SELECT * FROM schedules ORDER BY schedule_id"))
                assertEquals(sequences, db.sequences())
                assertEquals(listOf(listOf(801L)), db.query("INSERT INTO agents (session_id, parent_agent_id) VALUES (10, 91) RETURNING agent_id"))
                assertEquals(listOf(listOf(601L)), db.query(
                    """INSERT INTO schedules (agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, description, created_at)
                       VALUES (91, 0, 0, 'new', '$FirstTime') RETURNING schedule_id""",
                ))
            }
            assertContentEquals(before, homeTestFileSystem.readBytes(source))
        }

        test("invalid TPS rolls back all copied rows, with the snapshot untouched and detached") { directory ->
            val source = Path(directory, "source.db")
            source.newStandaloneDatabase { old ->
                old.installReleasedSchema()
                old.execute("INSERT INTO sessions VALUES (14, 1)")
                old.execute("INSERT INTO agents VALUES (60, 14, NULL, 0)")
                old.insertCommit(60L, "llm_request_meta_response", 0L, Response, ResponseTime)
            }
            val before = homeTestFileSystem.readBytes(source)
            Path(directory, "staging.db").newStandaloneDatabase { db ->
                val failure = assertFailsWith<IllegalStateException> { upgradeRecentHomeDatabase(db, source) }
                assertContains(failure.message.orEmpty(), "Cannot backfill recent response TPS")
                db.requireEmptyRecentTarget()
                assertEquals(listOf(listOf("main")), db.query("SELECT name FROM pragma_database_list"))
            }
            assertContentEquals(before, homeTestFileSystem.readBytes(source))
        }

        test("valid SQLite foreign keys do not hide rootless sessions or disconnected agent cycles") { directory ->
            val source = Path(directory, "source.db")
            source.newStandaloneDatabase { old ->
                old.installReleasedSchema()
                old.execute("INSERT INTO sessions VALUES (2, 0), (3, 0)")
                old.execute("INSERT INTO agents VALUES (25, 2, NULL, 0), (41, 2, 25, 0), (42, 2, 41, 0)")
            }
            Path(directory, "rootless-staging.db").newStandaloneDatabase { db ->
                val failure = assertFailsWith<IllegalStateException> { upgradeRecentHomeDatabase(db, source) }
                assertContains(failure.message.orEmpty(), "no root agent")
                db.requireEmptyRecentTarget()
            }
            openExistingSqliteConnection(source).use { old ->
                old.execute("INSERT INTO agents VALUES (26, 3, NULL, 0)")
                old.execute("UPDATE agents SET parent_agent_id=42 WHERE agent_id=41")
                assertEquals(emptyList(), old.query("PRAGMA foreign_key_check"))
            }
            Path(directory, "disconnected-staging.db").newStandaloneDatabase { db ->
                val failure = assertFailsWith<IllegalStateException> { upgradeRecentHomeDatabase(db, source) }
                assertContains(failure.message.orEmpty(), "disconnected from the root")
                db.requireEmptyRecentTarget()
            }
        }
    }
}

private fun SqliteConnection.requireEmptyRecentTarget() {
    requireFrozenHomeSchema(RecentUpgradeSchemaVersion)
    for (table in listOf("sessions", "agents", "agent_commits", "session_catalog_view",
        "schedules", "session_groups", "fork_context_recovery", "sqlite_sequence")) {
        assertEquals(emptyList(), query("SELECT 1 FROM main.$table LIMIT 1"), table)
    }
}

private fun SqliteConnection.installReleasedSchema() = transaction {
    RecentHomeSourceDdl.forEach { execute(it) }
    execute("INSERT INTO sqlite_sequence (name, seq) VALUES ('agents', 0)")
    execute("PRAGMA user_version=1")
}

private fun Path.newStandaloneDatabase(block: (SqliteConnection) -> Unit) =
    openSqliteConnection(this).use { db ->
        assertEquals(listOf(listOf("delete")), db.query("PRAGMA journal_mode=DELETE"))
        block(db)
    }

private fun SqliteConnection.schemaDump() =
    query("SELECT type, name, tbl_name, sql FROM main.sqlite_schema ORDER BY type, name")

private fun SqliteConnection.commitKeys() =
    query("""SELECT agent_id, type, "index", created_at FROM agent_commits ORDER BY agent_id, "index", type""")

private fun SqliteConnection.sequences() =
    query("SELECT rowid, name, seq FROM sqlite_sequence ORDER BY rowid")

private fun SqliteConnection.insertCommit(agent: Long, type: String, index: Long, payload: String, time: String) =
    execute("INSERT INTO agent_commits VALUES (?, ?, ?, ?, ?)", listOf(agent, type, index, payload, time))

private const val FirstTime = "2026-10-01T00:00:00Z"
private const val FixedFirstTime = "2026-10-01T00:00:00.000000000Z"
private const val BaseTime = "2026-10-01T00:00:00.500Z"
private const val ResponseTime = "2026-10-01T01:00:00.75+01:00"
private const val Response = """{"type":"llm_request_meta_response","usage":{"output_tokens":15}}"""
private const val Untouched = """{ "type": "assistant_message", "text": "keep bytes" }"""
