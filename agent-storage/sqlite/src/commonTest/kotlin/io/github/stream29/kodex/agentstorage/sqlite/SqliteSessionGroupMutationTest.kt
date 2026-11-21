package io.github.stream29.kodex.agentstorage.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

val sqliteSessionGroupMutationTest by testSuite {
    test("touches old and new cwd once, removes a deleted last member, and retains archived members") {
        openInMemorySqliteConnection().use { db ->
            db.seedGroupGuardFixture()
            assertNull(db.sessionCatalogGroup(10))
            assertEquals(mapOf<String?, Boolean>(null to true), db.changeRootGroup(10) {
                db.execute("INSERT INTO agents VALUES (10, 0)")
                db.execute("INSERT INTO session_catalog_view (root_agent_id) VALUES (10)")
            })
            assertEquals(SessionCatalogGroup(null), db.sessionCatalogGroup(10))
            assertEquals(listOf(listOf("", null, 1L)), db.groups())
            assertEquals(mapOf(null to false, "/a" to true), db.changeRootGroup(10) {
                db.execute("UPDATE session_catalog_view SET cwd = '/a' WHERE root_agent_id = 10")
            })
            db.changeRootGroup(11) {
                db.execute("INSERT INTO agents VALUES (11, 0)")
                db.execute("INSERT INTO session_catalog_view VALUES (11, '/a')")
            }
            db.changeRootGroup(11) { db.execute("UPDATE agents SET completed = 1 WHERE agent_id = 11") }
            assertEquals(mapOf<String?, Boolean>("/a" to true, "/b" to true), db.changeRootGroup(10) {
                db.execute("UPDATE session_catalog_view SET cwd = '/b' WHERE root_agent_id = 10")
            })
            assertEquals(listOf(listOf("/a", "/a", 4L), listOf("/b", "/b", 1L)), db.groups())
            assertEquals(mapOf<String?, Boolean>("/a" to false), db.changeRootGroup(11) {
                db.execute("DELETE FROM agents WHERE agent_id = 11")
            })
            assertEquals(listOf(listOf("/b", "/b", 1L)), db.groups())
        }
    }

    test("failed root and group writes roll back together; absent root is not the null group") {
        openInMemorySqliteConnection().use { db ->
            db.seedGroupGuardFixture()
            db.changeRootGroup(10) {
                db.execute("INSERT INTO agents VALUES (10, 0)")
                db.execute("INSERT INTO session_catalog_view VALUES (10, '/a')")
            }
            assertFailsWith<IllegalStateException> {
                db.transaction {
                    val before = db.sessionCatalogGroup(10)
                    db.execute("UPDATE session_catalog_view SET cwd = '/b' WHERE root_agent_id = 10")
                    db.recordSessionGroupMutation(before, db.sessionCatalogGroup(10))
                    error("Rollback")
                }
            }
            assertEquals(listOf(listOf("/a", "/a", 1L)), db.groups())
            assertEquals(SessionCatalogGroup("/a"), db.sessionCatalogGroup(10))
            assertEquals(emptyMap(), db.changeRootGroup(432) {})
        }
    }

    test("canonical activity text preserves exact offsets, fractions and nanos") {
        assertEquals("2026-11-21T01:00:00.000000000Z", catalogActivityTimestamp("2026-11-21T01:00:00Z"))
        assertEquals("2026-11-21T01:00:00.001000000Z", catalogActivityTimestamp("2026-11-21T09:00:00.001+08:00"))
        assertEquals("2026-11-21T01:00:00.001000001Z", catalogActivityTimestamp("2026-11-21T01:00:00.001000001Z"))
        assertFailsWith<IllegalArgumentException> { catalogActivityTimestamp("yesterday") }
    }
}

private fun SqliteConnection.seedGroupGuardFixture() {
    execute("CREATE TABLE agents (agent_id INTEGER PRIMARY KEY, completed INTEGER NOT NULL) STRICT")
    execute("CREATE TABLE session_catalog_view (root_agent_id INTEGER PRIMARY KEY REFERENCES agents(agent_id) ON DELETE CASCADE, cwd TEXT) STRICT")
    execute(CreateSessionGroupsTable)
}

private fun SqliteConnection.changeRootGroup(agentId: Int, write: () -> Unit): Map<String?, Boolean> = transaction {
    val before = sessionCatalogGroup(agentId)
    write()
    recordSessionGroupMutation(before, sessionCatalogGroup(agentId))
}

private fun SqliteConnection.groups(): List<List<Any?>> =
    query("SELECT cwd_key, cwd, group_nonce FROM session_groups ORDER BY cwd_key")
