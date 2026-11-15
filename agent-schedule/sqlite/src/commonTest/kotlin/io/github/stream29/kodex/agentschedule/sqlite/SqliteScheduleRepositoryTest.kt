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
}
