package io.github.stream29.kodex.agenteventbus

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ext.AskUserAlreadyAnswered
import io.github.stream29.kodex.agentstorage.contract.ext.AskUserEntryNotFound
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.tool.awaitevent.AwaitEventTools
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.mq.InMemoryMqBroker
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqDelivery
import io.github.stream29.kodex.utils.mq.MqHeaders
import io.github.stream29.kodex.utils.mq.MqMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

val agentInboxTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("inbox submissions persist user, ask, schedule and child events and restore exact ask errors") {
        runTest {
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(mockOpenAiClient { createResponse { assistantResponse() } }),
                timeSource = testScheduler.timeSource,
            )
            try {
                val broker = RecordingInboxBroker()
                val bus = AgentEventBus(repository, broker)
                assertSame(broker, bus.broker)
                val agentId = repository.create()
                val session = repository.open(agentId).also { it.initialize() }
                val entry = session.runtime.createAskUser(RequestUserInputArgs(listOf(
                    RequestUserInputQuestion(id = "which", header = "Choice", question = "Which one?"),
                )))
                val response = RequestUserInputResponse(mapOf("which" to RequestUserInputAnswer(listOf("one"))))
                val content = listOf(ContentItem.InputText("queued"), ContentItem.InputImage("https://example.invalid/image"))
                val directContent = listOf(ContentItem.InputText("captured session"))
                val creation = StableSubagentCreatedEvent(childAgentId = 91)

                bus.submitUserMessage(-1, emptyList())
                bus.submitUserMessage(session, directContent)
                session.runtime.runningTurn.value?.join()
                bus.recordSubagentCreation(session, creation)
                bus.submitUserMessage(agentId, content)
                session.runtime.runningTurn.value?.join()
                val missingAsk = assertFailsWith<AskUserEntryNotFound> { bus.submitAskUserAnswer(agentId, 99, response) }
                assertEquals(AskUserEntryNotFound(99).message, missingAsk.message)
                bus.submitAskUserAnswer(agentId, entry.askId, response)
                assertEquals(response, session.storage.getAskUsersAt(session.storage.latestIndex()).single().answer?.response)
                session.runtime.runningTurn.value?.join()
                val answered = assertFailsWith<AskUserAlreadyAnswered> { bus.submitAskUserAnswer(agentId, entry.askId, response) }
                assertEquals(AskUserAlreadyAnswered(entry.askId).message, answered.message)
                bus.submitScheduleTrigger(agentId, Long.MAX_VALUE, "nightly")
                session.runtime.runningTurn.value?.join()
                bus.submitSubagentCompletion(agentId, creation.childAgentId)
                session.runtime.runningTurn.value?.join()

                assertEquals(listOf("kodex.agent.inbox"), broker.declaredQueues)
                assertEquals(listOf(
                    AgentInboxMessage.UserMessage(agentId, content),
                    AgentInboxMessage.AskUserAnswer(agentId, 99, response),
                    AgentInboxMessage.AskUserAnswer(agentId, entry.askId, response),
                    AgentInboxMessage.AskUserAnswer(agentId, entry.askId, response),
                    AgentInboxMessage.ScheduleTrigger(agentId, Long.MAX_VALUE, "nightly"),
                    AgentInboxMessage.SubagentCompletion(agentId, creation.childAgentId),
                ), broker.requests.map { Json.decodeFromString<AgentInboxMessage>(it.body.decodeToString()) })
                assertEquals(listOf(true, false, true, false, true, true), broker.settled)
                assertEquals(6, broker.replyAddresses.toSet().size)

                val history = session.storage.getCommitsIn(
                    0..session.storage.latestIndex(), AgentCommitType.IndexTypes + AgentCommitType.WorkTypes,
                ).map { it.commit }
                assertEquals(listOf(StableUserMessage(directContent), StableUserMessage(content)), history.filterIsInstance<StableUserMessage>())
                assertEquals(1, history.filterIsInstance<StableAskUserEvent>().size)
                assertEquals(listOf(StableScheduleEvent(Long.MAX_VALUE, "nightly")), history.filterIsInstance<StableScheduleEvent>())
                assertEquals(listOf(creation), history.filterIsInstance<StableSubagentCreatedEvent>())
                assertEquals(listOf(StableSubagentCompletedEvent(creation.childAgentId)), history.filterIsInstance<StableSubagentCompletedEvent>())
            } finally {
                repository.cancelAndJoin()
            }
        }
    }

    test("only the matching await deadline reopens the evicted Agent and its confirmation waits for the check") {
        runTest {
            var wallTime = Instant.fromEpochSeconds(1234)
            var calls = 0
            val checking = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                createResponse {
                    if (++calls == 1) {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(
                                name = AwaitEventTools.Name,
                                callId = "deadline",
                                arguments = """{"name":"Wait for deadline","events":[{"type":"timeout","duration_ms":1000}]}""",
                            )),
                            ResponsesStreamEvent.Completed(Response(id = "pending", endTurn = false)),
                        )
                    } else {
                        checking.complete(Unit)
                        finish.await()
                        assistantResponse()
                    }
                }
            }
            val repository = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(client, object : Clock {
                    override fun now(): Instant = wallTime
                }),
                timeSource = testScheduler.timeSource,
            )
            try {
                val broker = RecordingInboxBroker()
                val bus = AgentEventBus(repository, broker)
                val agentId = repository.create()
                val session = repository.open(agentId).also { it.initialize() }
                bus.submitUserMessage(session, listOf(ContentItem.InputText("park")))
                session.runtime.runningTurn.value?.join()
                val expected = repository.pendingAwaitDeadlines.value.getValue(agentId)
                repository.closeSession(agentId)
                wallTime += 2.seconds

                bus.submitAwaitDeadline(agentId, expected.copy(snapshotIndex = expected.snapshotIndex + 1))
                bus.submitAwaitDeadline(agentId, expected.copy(callId = "another-await"))
                bus.submitAwaitDeadline(agentId, expected.copy(timeoutAt = wallTime))
                assertTrue(repository.openSessions.value.isEmpty())
                assertEquals(1, calls)
                assertEquals(listOf(true, true, true), broker.settled)

                val submission = async { bus.submitAwaitDeadline(agentId, expected) }
                checking.await()
                assertFalse(submission.isCompleted)
                val reopened = assertNotNull(repository.openSessions.value[agentId])
                val missing = assertFailsWith<IllegalArgumentException> { bus.submitScheduleTrigger(-1, 0L, "missing") }
                assertEquals("Agent -1 does not exist.", missing.message)
                finish.complete(Unit)
                submission.await()
                assertEquals(KodexAgentStateValue.AssistantMessage, reopened.runtime.state.value)
                assertNull(repository.pendingAwaitDeadlines.value[agentId])
                val completed = reopened.storage.getCommitsIn(
                    0..reopened.storage.latestIndex(), setOf(AgentCommitType.AwaitEventToolEvent),
                ).single().commit
                assertIs<StableAwaitEventResult.Output>(assertIs<StableAwait>(completed).result)

                repository.closeSession(agentId)
                bus.submitAwaitDeadline(agentId, expected)
                assertTrue(repository.openSessions.value.isEmpty())
                assertEquals(2, calls)
                assertEquals(listOf(true, true, true, false, true, true), broker.settled)
                assertEquals(
                    AgentInboxMessage.AwaitDeadlineTrigger(agentId, expected.snapshotIndex, expected.callId, expected.timeoutAt),
                    Json.decodeFromString<AgentInboxMessage>(broker.requests.last().body.decodeToString()),
                )
            } finally {
                repository.cancelAndJoin()
            }
        }
    }

    test("a competing consumer confirms a delivery requeued when the original consumer stops") {
        runTest {
            val first = InMemoryKodexSessionRepository(testKodexAgentDependencies(), timeSource = testScheduler.timeSource)
            val second = InMemoryKodexSessionRepository(
                testKodexAgentDependencies(mockOpenAiClient { createResponse { assistantResponse() } }),
                timeSource = testScheduler.timeSource,
            )
            try {
                val working = CompletableDeferred<Int>()
                val blocked = object : KodexSessionRepository by first {
                    override suspend fun openAgent(agentId: Int): KodexAgentSession? {
                        working.complete(agentId)
                        awaitCancellation()
                    }
                }
                val broker = RecordingInboxBroker()
                val bus = AgentEventBus(blocked, broker)
                val agentId = second.create()
                val session = second.open(agentId).also { it.initialize() }
                val content = listOf(ContentItem.InputText("redeliver"))
                val request = async { bus.submitUserMessage(agentId, content) }
                assertEquals(agentId, working.await())
                assertFalse(request.isCompleted)

                val competing = AgentEventBus(second, broker)
                competing.submitScheduleTrigger(agentId, 12L, "while first is busy")
                assertFalse(request.isCompleted)
                first.cancelAndJoin()
                request.await()
                session.runtime.runningTurn.value?.join()

                assertEquals(listOf(true, true), broker.settled)
                assertEquals(2, broker.requests.size)
                val history = session.storage.getCommitsIn(
                    0..session.storage.latestIndex(), AgentCommitType.IndexTypes + AgentCommitType.WorkTypes,
                ).map { it.commit }
                assertEquals(listOf(StableUserMessage(content)), history.filterIsInstance<StableUserMessage>())
                assertEquals(listOf(StableScheduleEvent(12L, "while first is busy")), history.filterIsInstance<StableScheduleEvent>())
            } finally {
                first.cancelAndJoin()
                second.cancelAndJoin()
            }
        }
    }

    test("invalid requests are rejected without poisoning the shared inbox") {
        runTest {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies(), timeSource = testScheduler.timeSource)
            try {
                val broker = RecordingInboxBroker()
                val bus = AgentEventBus(repository, broker)
                broker.requestQueue("kodex.agent.inbox", """{"type":"unrecognized"}""".encodeToByteArray())
                broker.requestQueue("kodex.agent.inbox", byteArrayOf(-1))
                assertFailsWith<IllegalArgumentException> { bus.submitUserMessage(-1, listOf(ContentItem.InputText("missing"))) }
                assertEquals(listOf(false, false, false), broker.settled)
            } finally {
                repository.cancelAndJoin()
            }
        }
    }
}

private suspend fun KodexAgentSession.initialize() {
    runtime.modify { it.initialize(KodexAgentSettings(OpenAiModelId("test-model"))) }
}

private fun assistantResponse() = flowOf(
    ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("done")),
    )),
    ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)),
)

private class RecordingInboxBroker(private val delegate: MqBroker = InMemoryMqBroker()) : MqBroker by delegate {
    val declaredQueues = mutableListOf<String>()
    val requests = mutableListOf<MqMessage>()
    val settled = mutableListOf<Boolean>()
    val replyAddresses = mutableListOf<String>()

    override fun declareQueue(queue: String) {
        declaredQueues += queue
        delegate.declareQueue(queue)
    }

    override suspend fun requestQueue(queue: String, body: ByteArray, headers: Map<String, String>): MqMessage {
        assertEquals("kodex.agent.inbox", queue)
        requests += MqMessage("", queue, body, headers)
        val correlationId = "test-request-${requests.size}"
        return delegate.requestQueue(queue, body, headers + (MqHeaders.CorrelationId to correlationId)).also {
            assertEquals(correlationId, it.headers[MqHeaders.CorrelationId])
        }
    }

    override fun consumeQueue(queue: String, prefetch: Int) = delegate.consumeQueue(queue, prefetch).map { incoming ->
        assertEquals("kodex.agent.inbox", queue)
        assertEquals("", incoming.message.exchange)
        assertEquals(queue, incoming.message.routingKey)
        assertNotNull(incoming.message.headers[MqHeaders.ReplyTo])
        object : MqDelivery by incoming {
            override suspend fun ack() {
                incoming.ack()
                settled += true
            }

            override suspend fun nack(requeue: Boolean) {
                assertFalse(requeue, "A rejected request must not be retried indefinitely")
                incoming.nack(requeue)
                settled += false
            }
        }
    }

    override fun replyTo(replyTo: String, body: ByteArray, headers: Map<String, String>): Boolean {
        replyAddresses += replyTo
        return delegate.replyTo(replyTo, body, headers)
    }
}
