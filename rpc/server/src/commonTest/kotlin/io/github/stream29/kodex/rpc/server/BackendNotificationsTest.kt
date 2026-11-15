package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.OpenAiModerationBlockedException
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
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
            val notifications = repository.stopEvents.toNotifications()
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("unobserved")))
            rpc.resume(index)

            val first = async(start = CoroutineStart.UNDISPATCHED) { notifications.first() }
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("observed-1")))
            rpc.resume(index)
            assertEquals(index, (first.await() as Notification.Stop).sessionIndex)

            rpc.appendUserMessage(index, listOf(ContentItem.InputText("between")))
            rpc.resume(index)

            val second = async(start = CoroutineStart.UNDISPATCHED) { notifications.first() }
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("observed-2")))
            rpc.resume(index)
            assertEquals(index, (second.await() as Notification.Stop).sessionIndex)
        }
    }

    test("mixed host waits publish one ordered list per type without executing any hook") {
        val question = PendingRequestUserInputToolEvent(
            "question",
            arguments = RequestUserInputArgs(
                questions = listOf(RequestUserInputQuestion("id", "header", "question")),
            ),
        )
        val suggestion = PendingSuggestSubagentTaskToolEvent(
            "suggest",
            arguments = SuggestSubagentTaskArgs(
                listOf(SuggestedSubagentTask("child", "prompt")),
            ),
        )
        val secondQuestion = question.copy(callId = "question-two")
        val client = mockOpenAiClient {
            createResponse {
                flow {
                    listOf(question, suggestion, secondQuestion).forEachIndexed { idx, pending ->
                        emit(
                            ResponsesStreamEvent.OutputItemDone(
                                idx.toLong(),
                                pending.toResponseHistoryItems().single() as ResponseItem.ToolCall,
                            ),
                        )
                    }
                    emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
                }
            }
        }
        runtimeFixture(client) { repository, index, rpc ->
            val notifications = async(start = CoroutineStart.UNDISPATCHED) {
                repository.stopEvents.toNotifications().take(2).toList()
            }
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("host")))
            rpc.resume(index)
            val result = notifications.await()
            assertEquals(listOf(question, secondQuestion), assertIs<Notification.Stop.RequestUserInput>(result[0]).requests)
            assertEquals(listOf(suggestion), assertIs<Notification.Stop.SuggestSubagent>(result[1]).requests)
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
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("run")))
            val run = async { runCatching { rpc.resume(index) } }
            entered.await()
            assertFailsWith<Throwable> { rpc.appendUserMessage(index, listOf(ContentItem.InputText("illegal"))) }
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
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("cancel")))
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
                rpc.appendUserMessage(index, listOf(ContentItem.InputText("run")))
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
                rpc.appendUserMessage(index, listOf(ContentItem.InputText("run")))
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
