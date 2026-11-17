@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentschedule.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlinx.coroutines.sync.Mutex
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

val sqliteScheduleRepositoryTest by testSuite {
    test("persists schedules in SQLite, preserves sub-second ordering, and reloads earliest trigger on reopen") {
        openInMemorySqliteConnection().use { connection ->
            val mutex = Mutex()
            val repo = SqliteScheduleRepository(connection, mutex)
            assertNull(repo.nextTriggerAt.value)

            val created = Instant.parse("2026-11-15T10:00:00Z")
            val exactSecond = Instant.parse("2026-11-15T10:05:00Z")
            val plusHalfSecond = Instant.parse("2026-11-15T10:05:00.500Z")
            val advanced = Instant.parse("2026-11-15T10:10:00Z")

            // Insert .500Z first and :00Z second to verify numeric (epochSeconds, nanoseconds) ordering beats ASCII string ordering.
            val later = repo.create(agentId = 7, triggerAt = plusHalfSecond, cron = "*/5 * * * *", description = "half", createdAt = created)
            val earlier = repo.create(agentId = 7, triggerAt = exactSecond, cron = null, description = "exact", createdAt = created)

            assertEquals(exactSecond, repo.nextTriggerAt.value)
            assertEquals(listOf(earlier, later), repo.list(agentId = 7))
            assertEquals(listOf(earlier), repo.listDue(exactSecond))
            assertEquals(listOf(earlier, later), repo.listDue(plusHalfSecond))

            // Reopening on the same connection initializes nextTriggerAt from persisted rows.
            val reopened = SqliteScheduleRepository(connection, mutex)
            assertEquals(exactSecond, reopened.nextTriggerAt.value)

            assertTrue(reopened.delete(earlier.scheduleId))
            assertEquals(plusHalfSecond, reopened.nextTriggerAt.value)

            assertTrue(reopened.advance(later.scheduleId, advanced))
            assertFalse(reopened.advance(999L, advanced))
            assertEquals(advanced, reopened.nextTriggerAt.value)

            assertTrue(reopened.delete(later.scheduleId))
            assertNull(reopened.nextTriggerAt.value)

            // Schedule IDs remain strictly monotonic even after deleting all existing schedules.
            val afterEmpty = reopened.create(agentId = 7, triggerAt = advanced, cron = null, description = "monotonic", createdAt = created)
            assertEquals(3L, afterEmpty.scheduleId)
            assertTrue(reopened.delete(afterEmpty.scheduleId))

            assertFailsWith<IllegalArgumentException> {
                reopened.create(agentId = 7, triggerAt = exactSecond, cron = "bad", description = "x", createdAt = created)
            }
        }
    }

    test("deleteForSessionInTransaction removes root and child agent schedules and refreshes nextTriggerAt") {
        openInMemorySqliteConnection().use { connection ->
            connection.execute("CREATE TABLE sessions (session_id INTEGER PRIMARY KEY)")
            connection.execute("CREATE TABLE agents (agent_id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL)")
            connection.execute("INSERT INTO sessions (session_id) VALUES (10), (20)")
            connection.execute("INSERT INTO agents (agent_id, session_id) VALUES (10, 10), (11, 10), (20, 20)")

            val mutex = Mutex()
            val repo = SqliteScheduleRepository(connection, mutex)
            val created = Instant.parse("2026-11-15T10:00:00Z")
            val t1 = Instant.parse("2026-11-15T10:01:00Z")
            val t2 = Instant.parse("2026-11-15T10:02:00Z")
            val t3 = Instant.parse("2026-11-15T10:03:00Z")

            repo.create(agentId = 10, triggerAt = t1, cron = null, description = "root", createdAt = created)
            repo.create(agentId = 11, triggerAt = t2, cron = null, description = "child", createdAt = created)
            val survivor = repo.create(agentId = 20, triggerAt = t3, cron = null, description = "other-session", createdAt = created)
            assertEquals(t1, repo.nextTriggerAt.value)

            connection.transaction {
                repo.deleteForSessionInTransaction(10)
                connection.execute("DELETE FROM agents WHERE session_id = 10")
                connection.execute("DELETE FROM sessions WHERE session_id = 10")
            }

            assertTrue(repo.list(10).isEmpty())
            assertTrue(repo.list(11).isEmpty())
            assertEquals(listOf(survivor), repo.list(20))
            assertEquals(t3, repo.nextTriggerAt.value)
        }
    }
}
