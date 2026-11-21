package io.github.stream29.kodex.agentstorage.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.sqlite.*
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Instant

private fun withSchema(block: (SqliteConnection) -> Unit) {
    openInMemorySqliteConnection().use { db ->
        initializeKodexHomeDatabase(db)
        requireEmptyKodexHomeDatabase(db)
        block(db)
    }
}

private fun SqliteConnection.session(id: Int) {
    execute("INSERT INTO sessions(session_id) VALUES(?)", listOf(id.toLong()))
    execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES(?, ?, NULL)", listOf(id.toLong(), id.toLong()))
    execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(?)", listOf(id.toLong()))
}

private fun SqliteConnection.put(agent: Int, type: String, index: Int, payload: String, createdAt: Instant = Instant.DISTANT_PAST) {
    execute("INSERT INTO agent_commits VALUES(?,?,?,?,?)", listOf(agent.toLong(), type, index.toLong(), payload, createdAt.toString()))
}

private fun settingsPayload(name: String, cwd: String): String = OpenAiJsonCodec.encodeToString(
    KodexAgentSettings.serializer(),
    KodexAgentSettings(model = OpenAiModelId("synthetic"), name = name, cwd = Path(cwd)),
)

private fun SqliteConnection.catalog(id: Int): List<Any?> =
    query("SELECT * FROM session_catalog_view WHERE root_agent_id = ?", listOf(id.toLong())).single()

val sqliteSchemaTest by testSuite {
    test("live DDL and constraints exclude global settings and retired commit types") { withSchema { db ->
        assertEquals(2L, db.query("PRAGMA user_version").single().single())
        assertEquals(KodexHomeDatabaseApplicationId.toLong(), db.query("PRAGMA application_id").single().single())
        assertEquals(setOf("sessions", "agents", "agent_commits", "session_catalog_view", "session_groups",
            "sqlite_sequence", "schedules", "fork_context_recovery"),
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] }.toSet())
        assertTrue(db.query("SELECT name FROM sqlite_master WHERE type = 'view'").isEmpty())
        db.session(0)
        assertFailsWith<SqliteFailure> { db.session(-1) }
        assertFailsWith<SqliteFailure> { db.put(999, "user_message", 0, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "missing", 0, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "index", 0, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "timestamp", 0, "\"2026-10-25T00:00:00Z\"") }
        assertFailsWith<SqliteFailure> { db.put(0, "token_count_response", 0, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "hosted_web_search", -1, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "hosted_web_search", 0, "malformed") }
        assertFailsWith<SqliteFailure> { db.execute("INSERT INTO agents(agent_id, session_id) VALUES(-1, 0)") }
        assertFailsWith<SqliteFailure> { db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES(99, 0, 888)") }
        assertFailsWith<SqliteFailure> { db.execute("INSERT INTO agents(session_id) VALUES(0)") }
    } }
    test("ordinary open rejects a directory-only v2 and never backfills it") { withSchema { db ->
        db.execute("DROP INDEX idx_session_catalog_view_cwd_updated")
        db.execute("DROP TABLE session_groups")
        assertFailsWith<IllegalStateException> { requireKodexHomeDatabase(db) }
        assertTrue(db.query("SELECT name FROM sqlite_schema WHERE name = 'session_groups'").isEmpty())
    } }
    test("rechecking signed schema never stamps an old database or reruns initialization") {
        openInMemorySqliteConnection().use { db ->
            db.execute("CREATE TABLE sessions (session_id INTEGER PRIMARY KEY)")
            db.execute("PRAGMA user_version=2")
            assertFailsWith<IllegalStateException> { requireKodexHomeDatabase(db) }
            assertFailsWith<IllegalStateException> { initializeKodexHomeDatabase(db) }
            assertEquals(listOf(listOf(0L)), db.query("PRAGMA application_id"))
            assertEquals(listOf(listOf("sessions")), db.query("SELECT name FROM sqlite_schema"))
        }
        withSchema { db ->
            assertFailsWith<IllegalStateException> { initializeKodexHomeDatabase(db) }
            db.session(0)
            db.execute("DELETE FROM sessions")
            requireKodexHomeDatabase(db)
            assertFailsWith<IllegalStateException> { requireEmptyKodexHomeDatabase(db) }
        }
    }
    test("catalog exact zero, latest settings, archive and revert semantics") { withSchema { db ->
        db.session(0)
        assertEquals(listOf(0L, null, null, null, null), db.catalog(0))
        val defaultPayload = """{"model":"synthetic"}"""
        OpenAiJsonCodec.decodeFromString(KodexAgentSettings.serializer(), defaultPayload)
        val t2 = Instant.fromEpochSeconds(-1, 999_999_998)
        db.put(0, "settings", 2, defaultPayload, createdAt = t2)
        db.recomputeSessionCatalog(0)
        assertEquals(listOf("", "."), db.catalog(0).subList(1, 3))
        assertNull(db.catalog(0)[3]) // created_at requires exact index 0, never the first later timestamp.
        assertEquals(t2.toString(), db.catalog(0)[4])
        val t0 = Instant.fromEpochSeconds(-2, 999_999_999)
        val t15 = Instant.fromEpochSeconds(0, 1)
        db.put(0, "user_message", 0, "{}", createdAt = t0)
        db.put(0, "settings", 12, settingsPayload("new", "/new"), createdAt = t2)
        db.put(0, "user_message", 15, "{}", createdAt = t15)
        db.recomputeSessionCatalog(0)
        assertEquals(listOf(0L, "new", "/new", t0.toString(), t15.toString()), db.catalog(0))
        assertTrue(db.query("SELECT * FROM session_catalog_view WHERE cwd='/old'").isEmpty())
        db.execute("""DELETE FROM agent_commits WHERE agent_id=0 AND "index">=12""")
        db.recomputeSessionCatalog(0)
        assertEquals(listOf(0L, "", ".", t0.toString(), t2.toString()), db.catalog(0))
        db.put(0, "assistant_message", 0, "{}", createdAt = t15)
        db.execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES(100, 0, 0)")
        db.put(100, "assistant_message", 0, "{}")
        db.put(100, "settings", 20, settingsPayload("child", "/child"), createdAt = Instant.fromEpochSeconds(1000))
        db.recomputeSessionCatalog(100)
        db.recomputeSessionCatalog(0)
        assertEquals(listOf(0L, "", ".", t15.toString(), t2.toString()), db.catalog(0))
        assertTrue(db.query("SELECT * FROM session_catalog_view WHERE root_agent_id=100").isEmpty())
    } }
    test("fork strict until, sparse boundaries and deleted Session index reuse") { withSchema { db ->
        db.session(0)
        val t0 = Instant.fromEpochSeconds(10, 7)
        val t9 = Instant.fromEpochSeconds(11, 9)
        db.put(0, "settings", 0, settingsPayload("original", "/old"), createdAt = t0)
        db.put(0, "settings", 4, settingsPayload("forkable", "/kept"), createdAt = t0)
        db.put(0, "settings", 9, settingsPayload("excluded", "/excluded"), createdAt = t9)
        assertEquals(listOf(0L, 0L, null), db.query("SELECT agent_id, session_id, parent_agent_id FROM agents WHERE agent_id=0").single())
        assertEquals(4L, db.query("""SELECT max("index") FROM agent_commits WHERE agent_id=0 AND type='settings' AND "index"<=7""").single().single())
        assertEquals(9L, db.query("""SELECT min("index") FROM agent_commits WHERE agent_id=0 AND type='settings' AND "index">=7""").single().single())
        assertTrue(db.query("""SELECT * FROM agent_commits WHERE agent_id=0 AND type='settings' AND "index"=7""").isEmpty())
        db.transaction {
            db.session(1)
            db.execute("""INSERT INTO agent_commits (agent_id, type, "index", payload_json, created_at) SELECT 1,type,"index",payload_json,created_at FROM agent_commits WHERE agent_id=0 AND "index"<9""")
            db.recomputeSessionCatalog(1)
        }
        assertEquals(listOf("forkable", "/kept", t0.toString(), t0.toString()), db.catalog(1).subList(1, 5))
        db.execute("DELETE FROM sessions WHERE session_id=0")
        assertTrue(db.query("SELECT * FROM agent_commits WHERE agent_id=0").isEmpty())
        assertTrue(db.query("SELECT * FROM session_catalog_view WHERE root_agent_id=0").isEmpty())
        db.execute("INSERT INTO sessions(session_id) VALUES(0)")
        val replacement = db.query("INSERT INTO agents(session_id) VALUES(0) RETURNING agent_id").single().single()
        assertEquals(2L, replacement)
        db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(2)")
        assertEquals(listOf(2L, null, null, null, null), db.catalog(2))
    } }
    test("history settings lookup retains the cwd expression index") { withSchema { db ->
        val plan = db.query("EXPLAIN QUERY PLAN SELECT agent_id FROM agent_commits WHERE type='settings' AND json_extract(payload_json,'$.cwd')='/cwd/1'")
        assertTrue("commits_settings_cwd" in plan.toString(), plan.toString())
    } }
}
