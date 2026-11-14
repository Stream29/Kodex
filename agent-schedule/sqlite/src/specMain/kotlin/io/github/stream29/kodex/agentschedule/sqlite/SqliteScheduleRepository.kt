@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentschedule.sqlite

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Instant

/**
 * SQLite-backed [ScheduleRepository] persisting the `schedules` table in `kodex.db`.
 */
public expect class SqliteScheduleRepository : ScheduleRepository {
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

/**
 * Opens a [SqliteScheduleRepository] sharing [connection] and [mutex] on `kodex.db`,
 * installing the `schedules` schema idempotently and initializing [ScheduleRepository.nextTriggerAt].
 */
public expect suspend fun SqliteScheduleRepository(
    connection: SqliteConnection,
    mutex: Mutex,
    requireAccess: () -> Unit = {},
): SqliteScheduleRepository
