@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentschedule.sqlite

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

public actual class SqliteScheduleRepository internal constructor(
    private val connection: SqliteConnection,
    private val mutex: Mutex,
    private val dispatcher: CoroutineDispatcher,
    private val requireAccess: () -> Unit,
    initialNextTriggerAt: Instant?,
) : ScheduleRepository {
    private val mutableNextTriggerAt: MutableStateFlow<Instant?> = MutableStateFlow(initialNextTriggerAt)

    public actual override val nextTriggerAt: StateFlow<Instant?> =
        mutableNextTriggerAt.asStateFlow()

    private suspend fun <T> read(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(dispatcher) {
                requireAccess()
                block()
            }
        }
    }

    private suspend fun <T> write(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(NonCancellable) {
                withContext(dispatcher) {
                    requireAccess()
                    connection.transaction {
                        block()
                    }
                }
            }
        }
    }

    public actual override suspend fun create(
        agentId: Int,
        triggerAt: Instant,
        cron: String?,
        description: String,
        createdAt: Instant,
    ): ScheduleRecord {
        require(agentId >= 0) { "Invalid agentId: $agentId" }
        val normalizedCron = cron?.trim()?.also { expr ->
            nextCronTriggerAt(expr, createdAt)
        }
        return write {
            connection.execute(
                """
                INSERT INTO schedules (
                    agent_id,
                    trigger_at_epoch_seconds,
                    trigger_at_nanoseconds,
                    cron,
                    description,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(
                    agentId.toLong(),
                    triggerAt.epochSeconds,
                    triggerAt.nanosecondsOfSecond.toLong(),
                    normalizedCron,
                    description,
                    createdAt.toString(),
                ),
            )
            val nextId = connection.query("SELECT last_insert_rowid()").single().single() as Long
            mutableNextTriggerAt.value = queryEarliestTriggerUnsafe(connection)
            ScheduleRecord(
                scheduleId = nextId,
                agentId = agentId,
                triggerAt = triggerAt,
                cron = normalizedCron,
                description = description,
                createdAt = createdAt,
            )
        }
    }

    public actual override suspend fun list(agentId: Int): List<ScheduleRecord> = read {
        val rows = connection.query(
            """
            SELECT schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at
            FROM schedules
            WHERE agent_id = ?
            ORDER BY trigger_at_epoch_seconds ASC, trigger_at_nanoseconds ASC, schedule_id ASC
            """.trimIndent(),
            listOf(agentId.toLong()),
        )
        rows.map(::decodeScheduleRow)
    }

    public actual override suspend fun listDue(now: Instant): List<ScheduleRecord> = read {
        val rows = connection.query(
            """
            SELECT schedule_id, agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, cron, description, created_at
            FROM schedules
            WHERE trigger_at_epoch_seconds < ?
               OR (trigger_at_epoch_seconds = ? AND trigger_at_nanoseconds <= ?)
            ORDER BY trigger_at_epoch_seconds ASC, trigger_at_nanoseconds ASC, schedule_id ASC
            """.trimIndent(),
            listOf(now.epochSeconds, now.epochSeconds, now.nanosecondsOfSecond.toLong()),
        )
        rows.map(::decodeScheduleRow)
    }

    public actual override suspend fun advance(
        scheduleId: Long,
        nextTriggerAt: Instant,
    ): Boolean = write {
        connection.execute(
            """
            UPDATE schedules
            SET trigger_at_epoch_seconds = ?,
                trigger_at_nanoseconds = ?
            WHERE schedule_id = ?
            """.trimIndent(),
            listOf(
                nextTriggerAt.epochSeconds,
                nextTriggerAt.nanosecondsOfSecond.toLong(),
                scheduleId,
            ),
        )
        if (connection.changes() == 0) return@write false
        mutableNextTriggerAt.value = queryEarliestTriggerUnsafe(connection)
        true
    }

    public actual override suspend fun delete(scheduleId: Long): Boolean = write {
        connection.execute(
            "DELETE FROM schedules WHERE schedule_id = ?",
            listOf(scheduleId),
        )
        if (connection.changes() == 0) return@write false
        mutableNextTriggerAt.value = queryEarliestTriggerUnsafe(connection)
        true
    }

    public actual fun deleteForSessionInTransaction(sessionId: Int) {
        connection.execute(
            "DELETE FROM schedules WHERE agent_id IN (SELECT agent_id FROM agents WHERE session_id = ?)",
            listOf(sessionId.toLong()),
        )
        if (connection.changes() > 0) {
            mutableNextTriggerAt.value = queryEarliestTriggerUnsafe(connection)
        }
    }
}

public actual suspend fun SqliteScheduleRepository(
    connection: SqliteConnection,
    mutex: Mutex,
    dispatcher: CoroutineDispatcher,
    requireAccess: () -> Unit,
): SqliteScheduleRepository {
    currentCoroutineContext().ensureActive()
    val earliest = mutex.withLock {
        withContext(dispatcher) {
            requireAccess()
            queryEarliestTriggerUnsafe(connection)
        }
    }
    return SqliteScheduleRepository(
        connection = connection,
        mutex = mutex,
        dispatcher = dispatcher,
        requireAccess = requireAccess,
        initialNextTriggerAt = earliest,
    )
}

private fun queryEarliestTriggerUnsafe(connection: SqliteConnection): Instant? =
    connection.query(
        """
        SELECT trigger_at_epoch_seconds, trigger_at_nanoseconds
        FROM schedules
        ORDER BY trigger_at_epoch_seconds ASC, trigger_at_nanoseconds ASC, schedule_id ASC
        LIMIT 1
        """.trimIndent(),
    ).firstOrNull()?.let { row ->
        Instant.fromEpochSeconds(row[0] as Long, (row[1] as Long).toInt())
    }

private fun decodeScheduleRow(row: List<Any?>): ScheduleRecord =
    ScheduleRecord(
        scheduleId = row[0] as Long,
        agentId = (row[1] as Long).toInt(),
        triggerAt = Instant.fromEpochSeconds(row[2] as Long, (row[3] as Long).toInt()),
        cron = row[4] as? String,
        description = row[5] as String,
        createdAt = Instant.parse(row[6] as String),
    )
