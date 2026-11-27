@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentschedule.sqlite

import io.github.stream29.kodex.agenteventbus.BackendMqExchanges
import io.github.stream29.kodex.agenteventbus.BackendSignalKeys
import io.github.stream29.kodex.agenteventbus.SignalDelivery
import io.github.stream29.kodex.agenteventbus.publishAfterCommit
import io.github.stream29.kodex.agenteventbus.subscribeSignals
import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import io.github.stream29.kodex.utils.mq.DefaultMqTopicBufferCapacity
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqHeaders
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

public actual class SqliteScheduleRepository internal constructor(
    private val connection: SqliteConnection,
    private val mutex: Mutex,
    private val dispatcher: CoroutineDispatcher,
    private val requireAccess: () -> Unit,
    initialNextTriggerAt: Instant?,
    private val broker: MqBroker,
    scope: CoroutineScope,
) : ScheduleRepository {
    private val originId = Uuid.random().toString()
    private val originHeaders = mapOf(MqHeaders.OriginId to originId)
    private val mutableNextTriggerAt: MutableStateFlow<Instant?> = MutableStateFlow(initialNextTriggerAt)

    public actual override val nextTriggerAt: StateFlow<Instant?> =
        mutableNextTriggerAt.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            broker.subscribeSignals(setOf(BackendSignalKeys.Schedules), DefaultMqTopicBufferCapacity).collect { delivery ->
                if (delivery is SignalDelivery.Signal && delivery.headers[MqHeaders.OriginId] == originId) return@collect
                read { mutableNextTriggerAt.value = queryEarliestTriggerUnsafe(connection) }
            }
        }
    }

    private suspend fun <T> read(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(dispatcher) {
                requireAccess()
                block()
            }
        }
    }

    private suspend fun <T : Any> write(block: () -> T?): T? {
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            withContext(NonCancellable) {
                withContext(dispatcher) {
                    requireAccess()
                    broker.publishAfterCommit(originHeaders) { signals ->
                        val update = connection.transaction {
                            block()?.let { it to queryEarliestTriggerUnsafe(connection) }
                        }
                        update?.let { (result, earliest) ->
                            mutableNextTriggerAt.value = earliest
                            signals.add(BackendSignalKeys.Schedules)
                            result
                        }
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
        return checkNotNull(write {
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
            ScheduleRecord(
                scheduleId = nextId,
                agentId = agentId,
                triggerAt = triggerAt,
                cron = normalizedCron,
                description = description,
                createdAt = createdAt,
            )
        })
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
        Unit.takeIf { connection.changes() != 0 }
    } != null

    public actual override suspend fun delete(scheduleId: Long): Boolean = write {
        connection.execute(
            "DELETE FROM schedules WHERE schedule_id = ?",
            listOf(scheduleId),
        )
        Unit.takeIf { connection.changes() != 0 }
    } != null

    public actual fun deleteForSessionInTransaction(sessionId: Int): () -> Unit {
        connection.execute(
            "DELETE FROM schedules WHERE agent_id IN (SELECT agent_id FROM agents WHERE session_id = ?)",
            listOf(sessionId.toLong()),
        )
        if (connection.changes() == 0) return {}
        val earliest = queryEarliestTriggerUnsafe(connection)
        return {
            mutableNextTriggerAt.value = earliest
            try {
                broker.tryPublish(BackendMqExchanges.Broadcast, BackendSignalKeys.Schedules, byteArrayOf(), originHeaders)
            } catch (_: Exception) {
                // The transaction has already committed; invalidation is best effort.
            }
        }
    }
}

public actual suspend fun CoroutineScope.SqliteScheduleRepository(
    connection: SqliteConnection,
    mutex: Mutex,
    broker: MqBroker,
    dispatcher: CoroutineDispatcher,
    requireAccess: () -> Unit,
): SqliteScheduleRepository {
    currentCoroutineContext().ensureActive()
    return mutex.withLock {
        withContext(dispatcher) {
            requireAccess()
            SqliteScheduleRepository(
                connection = connection,
                mutex = mutex,
                dispatcher = dispatcher,
                requireAccess = requireAccess,
                initialNextTriggerAt = queryEarliestTriggerUnsafe(connection),
                broker = broker,
                scope = this@SqliteScheduleRepository,
            )
        }
    }
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
