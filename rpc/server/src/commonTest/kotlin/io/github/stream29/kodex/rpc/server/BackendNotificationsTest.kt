package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.utils.mq.DefaultMqTopicBufferCapacity
import io.github.stream29.kodex.utils.mq.InMemoryMqBroker
import io.github.stream29.kodex.utils.stream.InMemoryStreamService

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agenteventbus.AgentNotificationSignal
import io.github.stream29.kodex.agenteventbus.NotificationDelivery
import io.github.stream29.kodex.agenteventbus.subscribeNotifications
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.OpenAiModerationBlockedException
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.utils.rpcexception.AgentRunFailureReported
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

val backendNotificationsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("no subscriber and resubscription never replay an old notification") {
        val client = mockOpenAiClient {
            createResponse {
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(
                        0,
                        ResponseItem.Message(
                            id = ResponseItemId("msg"),
                            role = MessageRole.Assistant,
                            content = listOf(ContentItem.OutputText("reply")),
                        ),
                    ),
                    ResponsesStreamEvent.Completed(Response(id = "resp", endTurn = true)),
                )
            }
        }
        runtimeFixture(client) { repository, index, rpc ->
            val session = repository.openSessions.value.getValue(index)
            val notifications = repository.stopEvents.onEach {
                val stop = assertIs<AgentStopEvent.AssistantMessage>(it.event)
                val commit = session.storage.getCommitExact(stop.commitIndex, setOf(stop.message.commitType))
                assertEquals(stop.message, commit?.commit)
            }.toNotifications()
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("unobserved")))
            rpc.resume(index)

            val first = async(start = CoroutineStart.UNDISPATCHED) { notifications.first() }
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("observed-1")))
            rpc.resume(index)
            assertEquals(index, (first.await() as Notification.Stop).sessionIndex)

            session.runtime.appendUserMessage(listOf(ContentItem.InputText("between")))
            rpc.resume(index)

            val second = async(start = CoroutineStart.UNDISPATCHED) { notifications.first() }
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("observed-2")))
            rpc.resume(index)
            assertEquals(index, (second.await() as Notification.Stop).sessionIndex)
        }
    }

    test("createAskUser publishes AskUserCreated immediately even while runningTurn is active") {
        val enteredTurn = CompletableDeferred<Unit>()
        val releaseTurn = CompletableDeferred<Unit>()
        val client = mockOpenAiClient {
            createResponse {
                flow {
                    enteredTurn.complete(Unit)
                    releaseTurn.await()
                    emit(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.Message(
                                role = MessageRole.Assistant,
                                content = listOf(ContentItem.OutputText("done")),
                            ),
                        ),
                    )
                    emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
                }
            }
        }
        runtimeFixture(client) { repository, index, _ ->
            val eventBus = AgentEventBus(repository, InMemoryMqBroker())
            val createdSignals = async(start = CoroutineStart.UNDISPATCHED) {
                eventBus.broker.subscribeNotifications(DefaultMqTopicBufferCapacity)
                    .filterIsInstance<NotificationDelivery.Signal>()
                    .map { it.signal }
                    .filterIsInstance<AgentNotificationSignal.AskUserCreated>()
                    .take(2)
                    .toList()
            }
            val rpc = BackendAgentRuntimeRpc(
                repository = repository,
                eventBus = eventBus,
                streamService = InMemoryStreamService(),
                client = client,
            )
            repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("start turn")))
            val running = async { rpc.resume(index) }
            enteredTurn.await()
            assertTrue(rpc.getRunningTurn(index))

            val args = RequestUserInputArgs(
                questions = listOf(RequestUserInputQuestion("id", "header", "question")),
            )
            val entry1 = rpc.createAskUser(index, args)
            val entry2 = rpc.createAskUser(index, args)
            assertEquals(
                listOf(
                    AgentNotificationSignal.AskUserCreated(index, index, entry1.askId),
                    AgentNotificationSignal.AskUserCreated(index, index, entry2.askId),
                ),
                createdSignals.await(),
            )

            releaseTurn.complete(Unit)
            running.await()
        }
    }

    test("final execution failure is a Stop but ordinary writes and cancellation are not") {
        val entered = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        runtimeFixture(
            mockOpenAiClient {
                createResponse {
                    flow {
                        entered.complete(Unit)
                        fail.await()
                        error("final provider failure")
                    }
                }
            },
        ) { repository, index, rpc ->
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                repository.stopEvents.toNotifications().first()
            }
            val session = repository.openSessions.value.getValue(index)
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("run")))
            val run = async { runCatching { rpc.resume(index) } }
            entered.await()
            assertFailsWith<Throwable> { session.runtime.appendUserMessage(listOf(ContentItem.InputText("illegal"))) }
            assertFalse(event.isCompleted)
            fail.complete(Unit)
            val failure = assertIs<AgentRunFailureReported>(run.await().exceptionOrNull())
            val stop = assertIs<Notification.Stop.UnhandledError>(event.await())
            assertEquals(index, stop.sessionIndex)
            assertNotNull(stop.message)
            assertEquals(stop.message, failure.diagnostic)
        }
        runtimeFixture(mockOpenAiClient { createResponse { flow { awaitCancellation() } } }) { repository, index, rpc ->
            val unexpected = async(start = CoroutineStart.UNDISPATCHED) {
                repository.stopEvents.toNotifications().first()
            }
            repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("cancel")))
            val run = async { rpc.resume(index) }
            rpc.getRunningTurnFlow(index).first { it }
            rpc.cancelRunningTurn(index)
            run.join()
            assertFalse(unexpected.isCompleted)
            unexpected.cancelAndJoin()
        }
    }

    test("moderation block reports one unhandled stop without retrying") {
        var attempts = 0
        val client = mockOpenAiClient {
            createResponse {
                attempts++
                flowOf(
                    ResponsesStreamEvent.Failed(
                        FailedResponse(
                            ResponseError(
                                code = "content_policy_violation",
                                message = "Authorization: Bearer secret; private prompt",
                            ),
                        ),
                    ),
                )
            }
        }
        runtimeFixture(client) { repository, index, rpc ->
            val events = mutableListOf<Notification>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.stopEvents.toNotifications().collect { events += it }
            }
            try {
                repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("run")))
                val failure = assertFailsWith<AgentRunFailureReported> {
                    rpc.resume(index)
                }
                yield()
                assertEquals(1, attempts)
                val stop = assertIs<Notification.Stop.UnhandledError>(events.single())
                assertEquals(stop.message, failure.diagnostic)
                assertTrue(stop.message.orEmpty().contains("moderation"))
                assertFalse(stop.message.orEmpty().contains("secret"))
                assertFalse(stop.message.orEmpty().contains("private prompt"))
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    test("manual compaction moderation failure uses the operation error path without a Stop") {
        var attempts = 0
        val client = mockOpenAiClient {
            createRemoteCompactionV2Response {
                attempts++
                throw OpenAiModerationBlockedException(OpenAiFailureReason.ContentPolicyViolation)
            }
        }
        runtimeFixture(client) { repository, index, rpc ->
            val events = mutableListOf<Notification>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.stopEvents.toNotifications().collect { events += it }
            }
            try {
                repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("run")))
                val failure = assertFailsWith<OpenAiModerationBlockedException> { rpc.forcedCompact(index) }
                yield()
                assertEquals(1, attempts)
                assertEquals(OpenAiFailureReason.ContentPolicyViolation, failure.failureDiagnostic?.reason)
                assertTrue(events.isEmpty())
            } finally {
                collector.cancelAndJoin()
            }
        }
    }
}
