@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentschedule.inmemory

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

public actual class InMemoryScheduleRepository public actual constructor() : ScheduleRepository {
    private val mutex: Mutex = Mutex()
    private var nextId: Long = 1L
    private val records: LinkedHashMap<Long, ScheduleRecord> = LinkedHashMap()
    private val mutableNextTriggerAt: MutableStateFlow<Instant?> = MutableStateFlow(null)

    public actual override val nextTriggerAt: StateFlow<Instant?> =
        mutableNextTriggerAt.asStateFlow()

    public actual override suspend fun create(
        agentId: Int,
        triggerAt: Instant,
        cron: String?,
        description: String,
        createdAt: Instant,
    ): ScheduleRecord {
        require(agentId >= 0) { "Invalid agentId: $agentId" }
        val normalizedCron = cron?.trim()?.also { expr ->
            // Validate 5-field cron syntax eagerly before persisting.
            nextCronTriggerAt(expr, createdAt)
        }
        return mutex.withLock {
            val id = nextId++
            val record = ScheduleRecord(
                scheduleId = id,
                agentId = agentId,
                triggerAt = triggerAt,
                cron = normalizedCron,
                description = description,
                createdAt = createdAt,
            )
            records[id] = record
            refreshNextTriggerAtLocked()
            record
        }
    }

    public actual override suspend fun list(agentId: Int): List<ScheduleRecord> =
        mutex.withLock {
            records.values
                .filter { it.agentId == agentId }
                .sortedWith(ScheduleOrder)
        }

    public actual override suspend fun listDue(now: Instant): List<ScheduleRecord> =
        mutex.withLock {
            records.values
                .filter { it.triggerAt <= now }
                .sortedWith(ScheduleOrder)
        }

    public actual override suspend fun advance(
        scheduleId: Long,
        nextTriggerAt: Instant,
    ): Boolean =
        mutex.withLock {
            val existing = records[scheduleId] ?: return@withLock false
            records[scheduleId] = existing.copy(triggerAt = nextTriggerAt)
            refreshNextTriggerAtLocked()
            true
        }

    public actual override suspend fun delete(scheduleId: Long): Boolean =
        mutex.withLock {
            val removed = records.remove(scheduleId) != null
            if (removed) {
                refreshNextTriggerAtLocked()
            }
            removed
        }

    private fun refreshNextTriggerAtLocked() {
        mutableNextTriggerAt.value = records.values.minOfOrNull { it.triggerAt }
    }
}

private val ScheduleOrder: Comparator<ScheduleRecord> =
    compareBy<ScheduleRecord> { it.triggerAt }.thenBy { it.scheduleId }
