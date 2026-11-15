@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentschedule.inmemory

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

val inMemoryScheduleRepositoryTest by testSuite {
    test("tracks nextTriggerAt, lists due schedules in order, advances cron, and deletes one-shot") {
        val repo = InMemoryScheduleRepository()
        assertNull(repo.nextTriggerAt.value)

        val t0 = Instant.parse("2026-11-15T10:00:00Z")
        val t1 = Instant.parse("2026-11-15T10:05:00Z")
        val t2 = Instant.parse("2026-11-15T10:10:00Z")
        val t3 = Instant.parse("2026-11-15T10:15:00Z")

        val second = repo.create(agentId = 1, triggerAt = t2, cron = "*/5 * * * *", description = "recurring", createdAt = t0)
        assertEquals(t2, repo.nextTriggerAt.value)

        val first = repo.create(agentId = 1, triggerAt = t1, cron = null, description = "one-shot", createdAt = t0)
        assertEquals(t1, repo.nextTriggerAt.value)

        val otherAgent = repo.create(agentId = 2, triggerAt = t2, cron = null, description = "other", createdAt = t0)

        assertEquals(listOf(first, second), repo.list(agentId = 1))
        assertEquals(listOf(first), repo.listDue(t1))
        assertEquals(listOf(first, second, otherAgent), repo.listDue(t2))

        assertTrue(repo.delete(first.scheduleId))
        assertFalse(repo.delete(first.scheduleId))
        assertEquals(t2, repo.nextTriggerAt.value)

        assertTrue(repo.advance(second.scheduleId, t3))
        assertTrue(repo.delete(otherAgent.scheduleId))
        assertEquals(t3, repo.nextTriggerAt.value)

        assertTrue(repo.delete(second.scheduleId))
        assertNull(repo.nextTriggerAt.value)

        assertFailsWith<IllegalArgumentException> {
            repo.create(agentId = 1, triggerAt = t1, cron = "invalid cron", description = "bad", createdAt = t0)
        }
    }
}
