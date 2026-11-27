@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agentsession.sqlite

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agenteventbus.AgentNotificationSignal
import io.github.stream29.kodex.agenteventbus.BackendMqExchanges
import io.github.stream29.kodex.agenteventbus.BackendSignalKeys
import io.github.stream29.kodex.agenteventbus.NotificationDelivery
import io.github.stream29.kodex.agenteventbus.publishSignal
import io.github.stream29.kodex.agenteventbus.subscribeNotifications
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.recordSessionGroupMutation
import io.github.stream29.kodex.agentstorage.sqlite.sessionCatalogGroup
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import io.github.stream29.kodex.utils.coroutines.use
import io.github.stream29.kodex.utils.mq.DefaultMqTopicBufferCapacity
import io.github.stream29.kodex.utils.mq.InMemoryMqBroker
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqHeaders
import io.github.stream29.kodex.utils.mq.MqMessage
import io.github.stream29.kodex.utils.mq.MqTopicSignal
import io.github.stream29.kodex.utils.sqlite.SqliteConnection
import io.github.stream29.kodex.utils.sqlite.openInMemorySqliteConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.time.Instant

private val settings = KodexAgentSettings(OpenAiModelId("test-model"))
private val deadlineTime = Instant.fromEpochSeconds(100)

private fun armedAwait(agentId: Int) = UnstableSnapshot(listOf(PendingAwait(
    callId = "wait",
    arguments = AwaitEventArguments(listOf(AwaitEventInput.Timeout(1000)), name = "Wait for timeout"),
    timeoutAt = deadlineTime,
    armedForAgentId = agentId,
)))

val sqliteRepositorySignalsTest by testSuite {
    test("shared broadcasts refresh peer groups, timeline, and deadlines, then evict deleted handles") {
        runTest {
            val broker = InMemoryMqBroker()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mutex = Mutex()
            openInMemorySqliteConnection().use { db ->
                initializeKodexHomeDatabase(db)
                testKodexAgentDependencies().use { deps ->
                    SqliteKodexSessionRepository(SystemTemporaryDirectory, db, mutex, deps, dispatcher = dispatcher, broker = broker).use { writer ->
                        val sessionId = writer.create()
                        val id = writer.getRootAgentId(sessionId)
                        val writerStorage = writer.open(sessionId).storage
                        writerStorage.commit(listOf(settings.copy(cwd = Path("/target"))))
                        writer.create()
                        SqliteKodexSessionRepository(SystemTemporaryDirectory, db, mutex, deps, dispatcher = dispatcher, broker = broker).use { reader ->
                            val handle = reader.open(sessionId)
                            val changes = mutableListOf<MqMessage>()
                            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                broker.subscribeTopic(BackendMqExchanges.Broadcast, setOf("#"), DefaultMqTopicBufferCapacity).collect {
                                    if (it is MqTopicSignal.Message) changes += it.message
                                }
                            }
                            val tail = writerStorage.commit(listOf(armedAwait(id)))
                            runCurrent()
                            assertEquals(tail, handle.storage.latestIndexFlow.value)
                            assertEquals(deadlineTime, reader.pendingAwaitDeadlines.value.getValue(id).timeoutAt)
                            assertEquals(1L, reader.sessionGroupNonce("/target").first())
                            assertEquals(0L, reader.sessionGroupNonce(null).first())
                            assertEquals(
                                setOf(BackendSignalKeys.SessionGroups, BackendSignalKeys.agentTimeline(id), BackendSignalKeys.agentAwaitDeadline(id)),
                                changes.map { it.routingKey }.toSet(),
                            )
                            assertTrue(changes.all { it.body.isEmpty() })
                            val writerOrigin = assertNotNull(changes.map { it.headers[MqHeaders.OriginId] }.distinct().single())

                            // Mutate without publication to distinguish an ignored loopback from an unchanged query.
                            val writerEpoch = writer.sessionGroupsNonce.first()
                            db.transaction {
                                val group = checkNotNull(db.sessionCatalogGroup(id))
                                db.recordSessionGroupMutation(group, group)
                            }
                            broker.publishSignal(BackendSignalKeys.SessionGroups, mapOf(MqHeaders.OriginId to writerOrigin))
                            runCurrent()
                            assertEquals(writerEpoch, writer.sessionGroupsNonce.first(), "Writer must ignore its own origin")
                            assertEquals(2L, reader.sessionGroupsNonce.first(), "The same origin is external to the reader")
                            broker.publishSignal(BackendSignalKeys.SessionGroups)
                            runCurrent()
                            assertEquals(2L, reader.sessionGroupsNonce.first(), "Unchanged groups do not advance the epoch")

                            val nonce = handle.storage.cacheNonce.value
                            writerStorage.revert(tail)
                            runCurrent()
                            assertEquals(tail - 1, handle.storage.latestIndexFlow.value)
                            assertTrue(handle.storage.cacheNonce.value > nonce)
                            assertFalse(id in reader.pendingAwaitDeadlines.value)

                            writerStorage.commit(listOf(armedAwait(id)))
                            runCurrent()
                            assertTrue(id in reader.pendingAwaitDeadlines.value)
                            writer.delete(sessionId)
                            runCurrent()
                            assertFalse(id in reader.openSessions.value)
                            assertTrue(handle.coroutineContext.job.isCompleted)
                            assertFalse(id in reader.pendingAwaitDeadlines.value)
                            assertEquals(writer.entries.value, reader.entries.value)
                            assertFalse(changes.any { it.routingKey == BackendSignalKeys.Schedules })
                        }
                    }
                }
            }
        }
    }

    test("rolled back SQLite session creation and history commits publish nothing") {
        runTest {
            val broker = InMemoryMqBroker()
            val observed = mutableListOf<MqMessage>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic(BackendMqExchanges.Broadcast, setOf("#"), DefaultMqTopicBufferCapacity).collect {
                    if (it is MqTopicSignal.Message) observed += it.message
                }
            }
            openInMemorySqliteConnection().use { db ->
                initializeKodexHomeDatabase(db)
                var rollback = true
                val connection = object : SqliteConnection by db {
                    override fun <T> transaction(block: () -> T): T = db.transaction {
                        block().also { check(!rollback) { "reject before COMMIT" } }
                    }
                }
                testKodexAgentDependencies().use { deps ->
                    SqliteKodexSessionRepository(
                        SystemTemporaryDirectory, connection, Mutex(), deps,
                        dispatcher = StandardTestDispatcher(testScheduler), broker = broker,
                    ).use { repo ->
                        assertFailsWith<IllegalStateException> { repo.create() }
                        runCurrent()
                        assertTrue(observed.isEmpty())
                        assertTrue(repo.entries.value.isEmpty())
                        assertEquals(emptyList(), repo.list())
                        assertEquals(0L, repo.sessionGroupsNonce.first())

                        rollback = false
                        val session = repo.open(repo.create())
                        runCurrent()
                        observed.clear()
                        rollback = true
                        val id = assertNotNull(session.storage.agentId)
                        assertFailsWith<IllegalStateException> { session.storage.commit(listOf(armedAwait(id))) }
                        runCurrent()
                        assertTrue(observed.isEmpty())
                        assertEquals(-1, session.storage.latestIndexFlow.value)
                        assertTrue(repo.pendingAwaitDeadlines.value.isEmpty())
                    }
                }
            }
        }
    }

    test("topic overflow recovers a missed inventory, timeline, deadline, and deletion") {
        runTest {
            val transport = InMemoryMqBroker()
            val limited = object : MqBroker by transport {
                override fun subscribeTopic(exchange: String, bindingKeys: Set<String>, bufferCapacity: Int) =
                    transport.subscribeTopic(exchange, bindingKeys, bufferCapacity = 1)
            }
            openInMemorySqliteConnection().use { db ->
                initializeKodexHomeDatabase(db)
                val dispatcher = StandardTestDispatcher(testScheduler)
                val mutex = Mutex()
                testKodexAgentDependencies().use { deps ->
                    SqliteKodexSessionRepository(SystemTemporaryDirectory, db, mutex, deps, InMemoryMqBroker(), dispatcher = dispatcher).use { writer ->
                        val keptSession = writer.create()
                        val removedSession = writer.create()
                        val kept = writer.getRootAgentId(keptSession)
                        val removed = writer.getRootAgentId(removedSession)
                        SqliteKodexSessionRepository(SystemTemporaryDirectory, db, mutex, deps, dispatcher = dispatcher, broker = limited).use { reader ->
                            val keptHandle = reader.open(keptSession)
                            val removedHandle = reader.open(removedSession)
                            writer.open(keptSession).storage.commit(listOf(armedAwait(kept)))
                            writer.delete(removedSession)
                            assertEquals(-1, keptHandle.storage.latestIndexFlow.value)
                            assertTrue(reader.pendingAwaitDeadlines.value.isEmpty())
                            assertEquals(listOf(keptSession, removedSession), reader.entries.value)

                            // Lose the only specific hint; the surviving hint targets a nonexistent Agent.
                            transport.publishSignal(BackendSignalKeys.agentDeleted(removed))
                            transport.publishSignal(BackendSignalKeys.agentTimeline(Int.MAX_VALUE))
                            runCurrent()
                            assertEquals(listOf(keptSession), reader.entries.value)
                            assertEquals(1L, reader.sessionGroupsNonce.first())
                            assertEquals(0, keptHandle.storage.latestIndexFlow.value)
                            assertEquals(deadlineTime, reader.pendingAwaitDeadlines.value.getValue(kept).timeoutAt)
                            assertEquals(setOf(kept), reader.openSessions.value.keys)
                            assertTrue(removedHandle.coroutineContext.job.isCompleted)
                        }
                    }
                }
            }
        }
    }

    test("running flips and both stop notification types are broadcast alongside the compatibility Flow") {
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var failResponse = false
            val client = mockOpenAiClient {
                createResponse {
                    flow {
                        if (failResponse) error("simulated provider failure")
                        entered.complete(Unit)
                        release.await()
                        emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                            role = MessageRole.Assistant,
                            content = listOf(ContentItem.OutputText("done")),
                        )))
                        emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
                    }
                }
            }
            val broker = InMemoryMqBroker()
            openInMemorySqliteConnection().use { db ->
                initializeKodexHomeDatabase(db)
                testKodexAgentDependencies(client).use { deps ->
                    SqliteKodexSessionRepository(
                        SystemTemporaryDirectory, db, Mutex(), deps,
                        dispatcher = StandardTestDispatcher(testScheduler), broker = broker,
                    ).use { repo ->
                        val sessionId = repo.create()
                        val session = repo.open(sessionId)
                        val id = repo.getRootAgentId(sessionId)
                        session.runtime.modify { it.initialize(settings) }
                        val runningSignals = mutableListOf<MqMessage>()
                        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            broker.subscribeTopic(BackendMqExchanges.Broadcast, setOf(BackendSignalKeys.agentRunning(id)), DefaultMqTopicBufferCapacity).collect {
                                if (it is MqTopicSignal.Message) runningSignals += it.message
                            }
                        }
                        val direct = async(start = CoroutineStart.UNDISPATCHED) { repo.stopEvents.first() }
                        val mq = async(start = CoroutineStart.UNDISPATCHED) { broker.subscribeNotifications(DefaultMqTopicBufferCapacity).first() }
                        session.runtime.appendUserMessage(listOf(ContentItem.InputText("begin")))
                        val turn = async { session.runtime.resume() }
                        entered.await()
                        runCurrent()
                        assertTrue(repo.listSessionGroups().single().hasRunning)
                        val origin = assertNotNull(runningSignals.single().headers[MqHeaders.OriginId])
                        release.complete(Unit)
                        turn.await()
                        val stop = assertIs<AgentStopEvent.AssistantMessage>(direct.await().event)
                        val notification = assertIs<NotificationDelivery.Signal>(mq.await())
                        assertEquals(AgentNotificationSignal.StopAssistantMessage(sessionId, id, stop.commitIndex), notification.signal)
                        assertEquals(origin, notification.headers[MqHeaders.OriginId])
                        runCurrent()
                        assertEquals(2, runningSignals.size)
                        assertTrue(runningSignals.all { it.body.isEmpty() })
                        assertFalse(repo.listSessionGroups().single().hasRunning)

                        failResponse = true
                        val directError = async(start = CoroutineStart.UNDISPATCHED) { repo.stopEvents.first() }
                        val mqError = async(start = CoroutineStart.UNDISPATCHED) { broker.subscribeNotifications(DefaultMqTopicBufferCapacity).first() }
                        session.runtime.appendUserMessage(listOf(ContentItem.InputText("fail")))
                        assertFailsWith<Exception> { session.runtime.resume() }
                        val stopError = assertIs<AgentStopEvent.UnhandledError>(directError.await().event)
                        assertEquals(
                            AgentNotificationSignal.StopUnhandledError(sessionId, id, stopError.message),
                            assertIs<NotificationDelivery.Signal>(mqError.await()).signal,
                        )
                    }
                }
            }
        }
    }
}
