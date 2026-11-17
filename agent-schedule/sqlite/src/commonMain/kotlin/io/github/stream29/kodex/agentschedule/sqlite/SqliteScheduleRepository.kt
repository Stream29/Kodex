@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentschedule.sqlite

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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

// language=SQL
internal const val SqliteSchedulesSchema: String = """
CREATE TABLE IF NOT EXISTS schedules (
    schedule_id INTEGER PRIMARY KEY AUTOINCREMENT CHECK (schedule_id >= 1),
    agent_id INTEGER NOT NULL CHECK (agent_id BETWEEN 0 AND 2147483647),
    trigger_at_epoch_seconds INTEGER NOT NULL,
    trigger_at_nanoseconds INTEGER NOT NULL CHECK (trigger_at_nanoseconds BETWEEN 0 AND 999999999),
    cron TEXT,
    description TEXT NOT NULL,
    created_at TEXT NOT NULL
) STRICT;

CREATE INDEX IF NOT EXISTS idx_schedules_trigger_at
    ON schedules(trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);

CREATE INDEX IF NOT EXISTS idx_schedules_agent_id
    ON schedules(agent_id, trigger_at_epoch_seconds, trigger_at_nanoseconds, schedule_id);
"""

public actual class SqliteScheduleRepository internal constructor(
    private val connection: SqliteConnection,
    private val mutex: Mutex,
    private val requireAccess: () -> Unit,
    initialNextTriggerAt: Instant?,
) : ScheduleRepository {
    private val mutableNextTriggerAt: MutableStateFlow<Instant?> = MutableStateFlow(initialNextTriggerAt)

    public actual override val nextTriggerAt: StateFlow<Instant?> =
        mutableNextTriggerAt.asStateFlow()

    private suspend fun <T> read(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                requireAccess()
                block()
            }
        }
    }

    private suspend fun <T> write(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
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
            "DELETE FROM schedules WHERE agent_id = ? OR agent_id IN (SELECT agent_id FROM agents WHERE session_id = ?)",
            listOf(sessionId.toLong(), sessionId.toLong()),
        )
        if (connection.changes() > 0) {
            mutableNextTriggerAt.value = queryEarliestTriggerUnsafe(connection)
        }
    }
}

public actual suspend fun SqliteScheduleRepository(
    connection: SqliteConnection,
    mutex: Mutex,
    requireAccess: () -> Unit,
): SqliteScheduleRepository {
    currentCoroutineContext().ensureActive()
    val earliest = mutex.withLock {
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                requireAccess()
                connection.transaction {
                    SqliteSchedulesSchema.split(';')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach(connection::execute)
                    queryEarliestTriggerUnsafe(connection)
                }
            }
        }
    }
    return SqliteScheduleRepository(
        connection = connection,
        mutex = mutex,
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
