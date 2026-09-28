package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

val backendNotificationsTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("no subscriber and resubscription never replay an old notification") {
        coroutineScope {
            val events = BackendNotifications()
            events.publish(Notification.Stop.UnhandledError(0, "old"))
            val first = async(start = CoroutineStart.UNDISPATCHED) { events.flow.first() }
            events.publish(Notification.Stop.UnhandledError(1, "first"))
            assertEquals(1, (first.await() as Notification.Stop).sessionIndex)
            events.publish(Notification.Stop.UnhandledError(2, "between"))
            val second = async(start = CoroutineStart.UNDISPATCHED) { events.flow.first() }
            events.publish(Notification.Stop.UnhandledError(3, "second"))
            assertEquals(3, (second.await() as Notification.Stop).sessionIndex)
        }
    }
    test("a slow subscriber loses old buffered values without suspending publication") {
        coroutineScope {
            val events = BackendNotifications()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val captured = mutableListOf<Int>()
            val collect = launch(start = CoroutineStart.UNDISPATCHED) {
                events.flow.take(65).collect {
                    captured += (it as Notification.Stop).sessionIndex
                    if (captured.size == 1) { entered.complete(Unit); release.await() }
                }
            }
            events.publish(Notification.Stop.UnhandledError(0, null))
            entered.await()
            for (index in 1..100) events.publish(Notification.Stop.UnhandledError(index, null))
            release.complete(Unit)
            collect.join()
            assertEquals(listOf(0) + (37..100).toList(), captured)
        }
    }
    test("mixed host waits publish one ordered list per type without executing any hook") {
        val question = PendingRequestUserInputToolEvent("question", arguments = RequestUserInputArgs(
            questions = listOf(RequestUserInputQuestion("id", "header", "question")),
        ))
        val suggestion = PendingSuggestSubagentTaskToolEvent("suggest", arguments = SuggestSubagentTaskArgs(
            listOf(SuggestedSubagentTask("child", "prompt")),
        ))
        val secondQuestion = question.copy(callId = "question-two")
        val client = mockOpenAiClient { createResponse { flow {
            listOf(question, suggestion, secondQuestion).forEachIndexed { index, pending ->
                emit(ResponsesStreamEvent.OutputItemDone(index.toLong(), pending.toResponseHistoryItems().single() as ResponseItem.ToolCall))
            }
            emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
        } } }
        runtimeFixture(client) { _, index, rpc ->
            val notifications = async(start = CoroutineStart.UNDISPATCHED) { rpc.notifications.flow.take(2).toList() }
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
        runtimeFixture(mockOpenAiClient { createResponse { flow {
            entered.complete(Unit)
            fail.await()
            error("final provider failure")
        } } }) { _, index, rpc ->
            val event = async(start = CoroutineStart.UNDISPATCHED) { rpc.notifications.flow.first() }
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("run")))
            val run = async { runCatching { rpc.resume(index) } }
            entered.await()
            assertFailsWith<Throwable> { rpc.appendUserMessage(index, listOf(ContentItem.InputText("illegal"))) }
            assertFalse(event.isCompleted)
            fail.complete(Unit)
            assertTrue(run.await().isFailure)
            val stop = assertIs<Notification.Stop.UnhandledError>(event.await())
            assertEquals(index, stop.sessionIndex)
            assertNotNull(stop.message)
        }
        runtimeFixture(mockOpenAiClient { createResponse { flow { awaitCancellation() } } }) { _, index, rpc ->
            val unexpected = async(start = CoroutineStart.UNDISPATCHED) { rpc.notifications.flow.first() }
            rpc.appendUserMessage(index, listOf(ContentItem.InputText("cancel")))
            val run = async { rpc.resume(index) }
            rpc.getRunningTurnFlow(index).first { it }
            rpc.cancelRunningTurn(index)
            run.join()
            assertFalse(unexpected.isCompleted)
            unexpected.cancelAndJoin()
        }
    }
}
