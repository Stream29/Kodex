@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentschedule.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agenteventbus.BackendMqExchanges
import io.github.stream29.kodex.agenteventbus.BackendSignalKeys
import io.github.stream29.kodex.agenteventbus.SignalDelivery
import io.github.stream29.kodex.agenteventbus.publishSignal
import io.github.stream29.kodex.agenteventbus.subscribeSignals
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.utils.mq.DefaultMqTopicBufferCapacity
import io.github.stream29.kodex.utils.mq.InMemoryMqBroker
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqHeaders
import io.github.stream29.kodex.utils.sqlite.SqliteFailure
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
val sqliteScheduleRepositoryTest by testSuite {
    test("persists schedules in SQLite, preserves sub-second ordering, and reloads earliest trigger on reopen") {
        runTest {
            openInMemorySqliteConnection().use { connection ->
                initializeKodexHomeDatabase(connection)
                val mutex = Mutex()
                val broker = InMemoryMqBroker()
                val repo = backgroundScope.SqliteScheduleRepository(connection, mutex, broker)
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
                val reopened = backgroundScope.SqliteScheduleRepository(connection, mutex, broker)
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

    test("deleteForSessionInTransaction removes root and child agent schedules and refreshes nextTriggerAt") {
        runTest {
            openInMemorySqliteConnection().use { connection ->
                initializeKodexHomeDatabase(connection)
                connection.execute("INSERT INTO sessions (session_id) VALUES (10), (20)")
                connection.execute("INSERT INTO agents (agent_id, session_id) VALUES (20, 10), (10, 20)")
                connection.execute("INSERT INTO agents (agent_id, session_id, parent_agent_id) VALUES (11, 10, 20)")

                val mutex = Mutex()
                val repo = backgroundScope.SqliteScheduleRepository(connection, mutex, InMemoryMqBroker())
                val created = Instant.parse("2026-11-15T10:00:00Z")
                val t1 = Instant.parse("2026-11-15T10:01:00Z")
                val t2 = Instant.parse("2026-11-15T10:02:00Z")
                val t3 = Instant.parse("2026-11-15T10:03:00Z")

                repo.create(agentId = 20, triggerAt = t1, cron = null, description = "root", createdAt = created)
                repo.create(agentId = 11, triggerAt = t2, cron = null, description = "child", createdAt = created)
                val survivor = repo.create(agentId = 10, triggerAt = t3, cron = null, description = "other-session", createdAt = created)
                assertEquals(t1, repo.nextTriggerAt.value)

                val afterCommit = connection.transaction {
                    repo.deleteForSessionInTransaction(10).also {
                        connection.execute("DELETE FROM agents WHERE session_id = 10")
                        connection.execute("DELETE FROM sessions WHERE session_id = 10")
                    }
                }
                afterCommit()

                assertTrue(repo.list(20).isEmpty())
                assertTrue(repo.list(11).isEmpty())
                assertEquals(listOf(survivor), repo.list(10))
                assertEquals(t3, repo.nextTriggerAt.value)
            }
        }
    }

    test("create, advance, and delete leave the projection and broker unchanged when COMMIT fails") {
        runTest {
            openInMemorySqliteConnection().use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("PRAGMA foreign_keys = ON")
                db.execute("CREATE TABLE test_parent(id INTEGER PRIMARY KEY)")
                db.execute("CREATE TABLE test_child(id INTEGER REFERENCES test_parent(id) DEFERRABLE INITIALLY DEFERRED)")
                val broker = InMemoryMqBroker()
                val observed = mutableListOf<SignalDelivery>()
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    broker.subscribeSignals(setOf(BackendSignalKeys.Schedules), DefaultMqTopicBufferCapacity).collect { observed += it }
                }
                val repo = backgroundScope.SqliteScheduleRepository(db, Mutex(), broker, StandardTestDispatcher(testScheduler))
                val first = Instant.fromEpochSeconds(10)
                val changed = Instant.fromEpochSeconds(20)

                db.execute("CREATE TRIGGER reject_insert AFTER INSERT ON schedules BEGIN INSERT INTO test_child VALUES (1); END")
                assertFailsWith<SqliteFailure> { repo.create(7, first, null, "rollback", first) }
                runCurrent()
                assertNull(repo.nextTriggerAt.value)
                assertTrue(observed.isEmpty())
                assertTrue(repo.list(7).isEmpty())

                db.execute("DROP TRIGGER reject_insert")
                val saved = repo.create(7, first, null, "saved", first)
                runCurrent()
                assertEquals(BackendSignalKeys.Schedules, (observed.single() as SignalDelivery.Signal).routingKey)
                observed.clear()

                db.execute("CREATE TRIGGER reject_update AFTER UPDATE ON schedules BEGIN INSERT INTO test_child VALUES (1); END")
                assertFailsWith<SqliteFailure> { repo.advance(saved.scheduleId, changed) }
                runCurrent()
                assertEquals(first, repo.nextTriggerAt.value)
                assertTrue(observed.isEmpty())

                db.execute("CREATE TRIGGER reject_delete AFTER DELETE ON schedules BEGIN INSERT INTO test_child VALUES (1); END")
                assertFailsWith<SqliteFailure> { repo.delete(saved.scheduleId) }
                runCurrent()
                assertEquals(first, repo.nextTriggerAt.value)
                assertEquals(listOf(saved), repo.list(7))
                assertTrue(observed.isEmpty())
            }
        }
    }

    test("shared broker refreshes scoped peers after schedule writes and session cleanup") {
        runTest {
            openInMemorySqliteConnection().use { db ->
                initializeKodexHomeDatabase(db)
                db.execute("INSERT INTO sessions(session_id) VALUES (1)")
                db.execute("INSERT INTO agents(agent_id, session_id) VALUES (7, 1)")
                val broker = InMemoryMqBroker()
                val mutex = Mutex()
                val dispatcher = StandardTestDispatcher(testScheduler)
                val writer = backgroundScope.SqliteScheduleRepository(db, mutex, broker, dispatcher)
                val reader = backgroundScope.SqliteScheduleRepository(db, mutex, broker, dispatcher)
                val first = Instant.fromEpochSeconds(10)
                val second = Instant.fromEpochSeconds(20)
                val saved = writer.create(7, first, null, "schedule", first)
                runCurrent()
                assertEquals(first, reader.nextTriggerAt.value)
                assertTrue(writer.advance(saved.scheduleId, second))
                runCurrent()
                assertEquals(second, reader.nextTriggerAt.value)
                assertTrue(writer.delete(saved.scheduleId))
                runCurrent()
                assertNull(reader.nextTriggerAt.value)

                writer.create(7, first, null, "remove with session", first)
                runCurrent()
                assertFailsWith<IllegalStateException> {
                    db.transaction {
                        writer.deleteForSessionInTransaction(1)
                        error("outer transaction rolls back")
                    }
                }
                assertEquals(first, writer.nextTriggerAt.value)
                assertEquals(first, reader.nextTriggerAt.value)
                val observed = mutableListOf<SignalDelivery>()
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    broker.subscribeSignals(setOf(BackendSignalKeys.Schedules), DefaultMqTopicBufferCapacity).collect { observed += it }
                }
                val afterCommit = db.transaction { writer.deleteForSessionInTransaction(1) }
                assertEquals(first, writer.nextTriggerAt.value, "Do not mutate before after-commit runs")
                afterCommit()
                assertNull(writer.nextTriggerAt.value, "Local projection updates synchronously after commit")
                runCurrent()
                assertNull(reader.nextTriggerAt.value)
                assertEquals(1, observed.size)
                db.transaction { writer.deleteForSessionInTransaction(1) }()
                runCurrent()
                assertEquals(1, observed.size, "No signal when session has no schedules")
            }
        }
    }

    test("schedule overflow forces a reread even when the remaining hint is a loopback") {
        runTest {
            openInMemorySqliteConnection().use { db ->
                initializeKodexHomeDatabase(db)
                val transport = InMemoryMqBroker()
                val limited = object : MqBroker by transport {
                    override fun subscribeTopic(exchange: String, bindingKeys: Set<String>, bufferCapacity: Int) =
                        transport.subscribeTopic(exchange, bindingKeys, bufferCapacity = 1)
                }
                val repo = backgroundScope.SqliteScheduleRepository(db, Mutex(), limited, StandardTestDispatcher(testScheduler))
                val observed = mutableListOf<SignalDelivery.Signal>()
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.subscribeSignals(setOf(BackendSignalKeys.Schedules), DefaultMqTopicBufferCapacity).collect {
                        if (it is SignalDelivery.Signal) observed += it
                    }
                }
                val first = Instant.fromEpochSeconds(10)
                repo.create(7, first, null, "schedule", first)
                runCurrent()
                val self = assertNotNull(observed.single().headers[MqHeaders.OriginId])
                val originHeaders = mapOf(MqHeaders.OriginId to self)

                db.execute("DELETE FROM schedules")
                transport.publishSignal(BackendSignalKeys.Schedules, originHeaders)
                runCurrent()
                assertEquals(first, repo.nextTriggerAt.value)

                assertTrue(transport.tryPublish(BackendMqExchanges.Broadcast, BackendSignalKeys.Schedules, byteArrayOf()))
                assertTrue(transport.tryPublish(BackendMqExchanges.Broadcast, BackendSignalKeys.Schedules, byteArrayOf(), originHeaders))
                runCurrent()
                assertNull(repo.nextTriggerAt.value)
            }
        }
    }
}
