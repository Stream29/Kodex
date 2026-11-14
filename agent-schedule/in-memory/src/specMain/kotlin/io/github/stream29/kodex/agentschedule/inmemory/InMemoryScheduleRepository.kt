@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentschedule.inmemory

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Process-local in-memory [ScheduleRepository]. */
public expect class InMemoryScheduleRepository() : ScheduleRepository {
    override val nextTriggerAt: StateFlow<Instant?>
    override suspend fun create(
        agentId: Int,
        triggerAt: Instant,
        cron: String?,
        description: String,
        createdAt: Instant,
    ): ScheduleRecord

    override suspend fun list(agentId: Int): List<ScheduleRecord>
    override suspend fun listDue(now: Instant): List<ScheduleRecord>
    override suspend fun advance(scheduleId: Long, nextTriggerAt: Instant): Boolean
    override suspend fun delete(scheduleId: Long): Boolean
}
