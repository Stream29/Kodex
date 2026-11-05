package io.github.stream29.kodex.agentstorage.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.io.files.Path
import io.github.stream29.kodex.utils.sqlite.*
import kotlinx.serialization.encodeToString
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.TimeSource

private fun withSchema(block: (SqliteConnection) -> Unit) {
    openInMemorySqliteConnection().use { db ->
        initializeSqlitePhaseOneSchema(db)
        block(db)
    }
}

private fun SqliteConnection.session(id: Int, archived: Boolean = false) {
    execute("INSERT INTO sessions(session_id, archived) VALUES(?, ?)", listOf(id.toLong(), if (archived) 1L else 0L))
    execute("INSERT INTO agents(agent_id, session_id, parent_agent_id) VALUES(?, ?, NULL)", listOf(id.toLong(), id.toLong()))
}

private fun SqliteConnection.put(agent: Int, type: String, index: Int, payload: String) {
    execute("INSERT INTO agent_commits VALUES(?,?,?,?)", listOf(agent.toLong(), type, index.toLong(), payload))
}

private fun SqliteConnection.timestamp(agent: Int, index: Int, seconds: Long, nanos: Int = 0) {
    put(agent, "timestamp", index, OpenAiJsonCodec.encodeToString(Instant.fromEpochSeconds(seconds, nanos)))
}

private fun settingsPayload(name: String, cwd: String): String = OpenAiJsonCodec.encodeToString(
    KodexAgentSettings.serializer(),
    KodexAgentSettings(model = OpenAiModelId("synthetic"), threadName = name, cwd = Path(cwd)),
)

private val scaleWorkPayload: String = OpenAiJsonCodec.encodeToString(
    StableWebSearchCall.serializer(),
    StableWebSearchCall(ResponseItem.WebSearchCall(status = "completed")),
)

private fun SqliteConnection.catalog(id: Int): List<Any?> = query("SELECT * FROM session_catalog WHERE session_id=?", listOf(id.toLong())).single()

val sqliteSchemaTest by testSuite {
    test("phase one DDL and constraints exclude global settings") { withSchema { db ->
        assertEquals(1L, db.query("PRAGMA user_version").single().single())
        assertEquals(setOf("sessions", "agents", "agent_commits", "session_catalog"), db.query("SELECT name FROM sqlite_master WHERE type IN ('table','view')").map { it[0] }.toSet())
        db.session(0)
        assertFailsWith<SqliteFailure> { db.session(-1) }
        assertFailsWith<SqliteFailure> { db.put(999, "user_message", 0, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "missing", 0, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "index", 0, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "hosted_web_search", -1, "{}") }
        assertFailsWith<SqliteFailure> { db.put(0, "hosted_web_search", 0, "malformed") }
        assertFailsWith<SqliteFailure> { db.execute("INSERT INTO agents VALUES(-1, 0, NULL)") }
        assertFailsWith<SqliteFailure> { db.execute("INSERT INTO agents VALUES(99, 0, 888)") }
    } }
    test("catalog exact zero, latest settings, archive and revert semantics") { withSchema { db ->
        db.session(0)
        assertEquals(listOf(0L, 0L, null, null, null, null), db.catalog(0))
        val defaultPayload = """{"model":"synthetic"}"""
        OpenAiJsonCodec.decodeFromString(KodexAgentSettings.serializer(), defaultPayload)
        db.put(0, "settings", 3, defaultPayload)
        assertEquals(listOf("", "."), db.catalog(0).subList(2,4))
        val t2 = Instant.fromEpochSeconds(-1, 999_999_998)
        db.timestamp(0, 2, -1, 999_999_998)
        assertNull(db.catalog(0)[4]) // created_at requires exact index 0, never the first later timestamp.
        assertEquals(t2.toString(), db.catalog(0)[5])
        val t0 = Instant.fromEpochSeconds(-2, 999_999_999)
        val t15 = Instant.fromEpochSeconds(0, 1)
        db.timestamp(0, 0, -2, 999_999_999)
        db.put(0, "settings", 12, settingsPayload("new", "/new"))
        db.timestamp(0, 15, 0, 1)
        db.execute("UPDATE sessions SET archived=1 WHERE session_id=0")
        assertEquals(listOf(0L,1L,"new","/new",t0.toString(),t15.toString()), db.catalog(0))
        assertTrue(db.query("SELECT * FROM session_catalog WHERE cwd='/old'").isEmpty())
        db.execute("""DELETE FROM agent_commits WHERE agent_id=0 AND "index">=12""")
        assertEquals(listOf(0L,1L,"",".",t0.toString(),t2.toString()), db.catalog(0))
        db.execute("INSERT INTO agents VALUES(100, 0, 0)")
        db.put(100, "settings", 20, settingsPayload("child", "/child"))
        db.timestamp(100, 20, 1000)
        assertEquals("", db.catalog(0)[2])
    } }
    test("fork strict until, sparse boundaries and deleted index reuse") { withSchema { db ->
        db.session(0)
        db.put(0, "settings", 0, settingsPayload("original", "/old"))
        db.put(0, "settings", 4, settingsPayload("forkable", "/kept"))
        db.put(0, "settings", 9, settingsPayload("excluded", "/excluded"))
        val t0 = Instant.fromEpochSeconds(10, 7)
        db.timestamp(0, 0, 10, 7)
        db.timestamp(0, 9, 11, 9)
        assertEquals(listOf(0L, 0L, null), db.query("SELECT agent_id, session_id, parent_agent_id FROM agents WHERE agent_id=0").single())
        assertEquals(4L, db.query("""SELECT max("index") FROM agent_commits WHERE agent_id=0 AND type='settings' AND "index"<=7""").single().single())
        assertEquals(9L, db.query("""SELECT min("index") FROM agent_commits WHERE agent_id=0 AND type='settings' AND "index">=7""").single().single())
        assertTrue(db.query("""SELECT * FROM agent_commits WHERE agent_id=0 AND type='settings' AND "index"=7""").isEmpty())
        db.transaction {
            db.session(1)
            db.execute("""INSERT INTO agent_commits SELECT 1,type,"index",payload_json FROM agent_commits WHERE agent_id=0 AND "index"<9""")
        }
        assertEquals(listOf("forkable","/kept",t0.toString(),t0.toString()), db.catalog(1).subList(2,6))
        db.execute("DELETE FROM sessions WHERE session_id=0")
        assertTrue(db.query("SELECT * FROM agent_commits WHERE agent_id=0").isEmpty())
        db.session(0)
        assertEquals(listOf(0L,0L,null,null,null,null), db.catalog(0))
    } }
    test("controlled catalog scale and query plan use composite primary key") { withSchema { db ->
        for (sessionCount in listOf(100, 1000)) {
            db.transaction {
                val first = if (sessionCount == 100) 0 else 100
                for (session in first until sessionCount) {
                    db.session(session, archived = session % 7 == 0)
                    if (session % 10 == 0) continue
                    for (history in 0 until (8 + (session % 3) * 8)) {
                        val index = history * 7
                        db.put(session, "settings", index, settingsPayload("s$session", "/cwd/${history % 4}"))
                        db.timestamp(session, index, (session * 1000 + history).toLong(), history * 999)
                        db.put(session, "hosted_web_search", index, scaleWorkPayload)
                    }
                }
            }
            val mark = TimeSource.Monotonic.markNow()
            repeat(5) {
                val rows = db.query("SELECT session_id FROM session_catalog WHERE archived=0 AND cwd='/cwd/3' ORDER BY session_id DESC")
                val expected = (0 until sessionCount).filter { it % 7 != 0 && it % 10 != 0 }.reversed().map(Int::toLong)
                assertEquals(expected, rows.map { it[0] })
            }
            val elapsed = mark.elapsedNow()
            val plan = db.query("EXPLAIN QUERY PLAN SELECT * FROM session_catalog WHERE archived=0 AND cwd='/cwd/3' ORDER BY session_id DESC").map { it.last().toString() }
            assertTrue(plan.any { "SEARCH st USING PRIMARY KEY" in it }, plan.toString())
            assertTrue(plan.any { "SEARCH ut USING PRIMARY KEY" in it }, plan.toString())
            assertTrue(plan.none { "SCAN agent_commits" in it }, plan.toString())
            val historyPlan = db.query("EXPLAIN QUERY PLAN SELECT agent_id FROM agent_commits WHERE type='settings' AND json_extract(payload_json,'$.cwd')='/cwd/1'").joinToString()
            assertTrue("commits_settings_cwd" in historyPlan, historyPlan)
            println("SQLITE_CATALOG_SCALE engine=${sqlite3Version()} sessions=$sessionCount history=8/16/24 executions=5 elapsed=$elapsed plan=$plan historyPlan=$historyPlan")
            assertTrue(elapsed.inWholeMilliseconds < 5000, "Five warm catalog queries exceeded 5s: $elapsed")
        }
    } }
}
