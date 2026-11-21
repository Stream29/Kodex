package io.github.stream29.kodex.agentsession.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val sqliteSessionGroupReadsTest by testSuite {
    test("group pages use independent Session numbers, newest-first indexed order and exact hidden count") {
        openInMemorySqliteConnection().use { db ->
            db.execute("CREATE TABLE agents (agent_id INTEGER PRIMARY KEY, session_id INTEGER, completed INTEGER) STRICT")
            db.execute("""CREATE TABLE session_catalog_view (
                root_agent_id INTEGER PRIMARY KEY, name TEXT, cwd TEXT, created_at TEXT, updated_at TEXT
            ) STRICT""")
            db.execute("CREATE INDEX idx_session_catalog_view_cwd_updated ON session_catalog_view (cwd, updated_at DESC, root_agent_id DESC)")
            db.execute("CREATE TABLE session_groups (cwd_key TEXT PRIMARY KEY, cwd TEXT UNIQUE, group_nonce INTEGER) STRICT")
            db.execute("INSERT INTO session_groups VALUES ('/a','/a',3), ('/archived','/archived',7), ('',NULL,1)")
            db.execute("INSERT INTO agents VALUES (10,101,0), (11,102,0), (12,103,1), (13,104,0), (14,105,1), (15,106,0)")
            db.execute("""INSERT INTO session_catalog_view VALUES
                (10,'whole','/a',NULL,'2026-11-21T01:00:00.000000000Z'),
                (11,'millisecond','/a',NULL,'2026-11-21T01:00:00.001000000Z'),
                (12,'archived-tie','/a',NULL,'2026-11-21T01:00:00.001000000Z'),
                (13,'unknown','/a',NULL,NULL),
                (14,'archived-group','/archived',NULL,'2026-11-21T01:00:00.001000001Z'),
                (15,'unset',NULL,NULL,'2026-11-21T01:00:00.001000002Z')
            """)
            assertEquals(listOf("/archived", "/a", null), db.readSessionGroupHeads().map { it.cwd })
            val limited = db.readSessionGroupPage("/a", false, 2)
            assertEquals(3L, limited.nonce)
            assertEquals(listOf(102L, 101L), limited.rows.map { it.first() })
            assertEquals(1, limited.hiddenCount)
            val all = db.readSessionGroupPage("/a", true, null)
            assertEquals(listOf(103L, 102L, 101L, 104L), all.rows.map { it.first() })
            assertEquals(0, all.hiddenCount)
            assertTrue(db.readSessionGroupPage("/archived", false, 5).rows.isEmpty())
            assertEquals(listOf(106L), db.readSessionGroupPage(null, false, 5).rows.map { it.first() })
            assertEquals(0L, db.readSessionGroupPage("/missing", true, 5).nonce)
        }
    }
}
