@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentschedule.sqlite

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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

    /**
     * Deletes schedules targeting [sessionId] or any child `agents` row belonging to [sessionId]
     * inside an already-open transaction on the shared `kodex.db` connection while holding the
     * shared database [Mutex] on the connection's dispatcher (before `DELETE FROM sessions`
     * cascades `agents`).
     *
     * Returns an after-commit callback that updates local [nextTriggerAt] and publishes
     * `schedules` when at least one schedule was deleted (or a no-op otherwise). Call it only
     * after the enclosing transaction commits, never on rollback.
     */
    public fun deleteForSessionInTransaction(sessionId: Int): () -> Unit
}

/**
 * Opens a [SqliteScheduleRepository] borrowing [connection], [mutex] and [dispatcher] on `kodex.db`,
 * reading its already-installed `schedules` schema to initialize [ScheduleRepository.nextTriggerAt]
 * and observing invalidations from [broker] until the receiver [CoroutineScope] is cancelled.
 * @throws io.github.stream29.kodex.utils.sqlite.SqliteFailure if the schema cannot be read.
 */
public expect suspend fun CoroutineScope.SqliteScheduleRepository(
    connection: SqliteConnection,
    mutex: Mutex,
    broker: MqBroker,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    requireAccess: () -> Unit = {},
): SqliteScheduleRepository
