package io.github.stream29.kodex.agentruntime.decorator.subagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.impl.KodexAgentState as createKodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FailedResponse
import io.github.stream29.kodex.openai.IncompleteResponse
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseError
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

val subagentParentNotificationRuntimeTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("successful resume sends its newest persisted assistant message") {
            val notifications = mutableListOf<StableCleanEvent.AgentMessage>()
            val firstAssistant = assistantMessage("intermediate result")
            val lastAssistant = assistantMessage("child result")
            val completed = ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true))
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = firstAssistant),
                ResponsesStreamEvent.OutputItemDone(outputIndex = 1, item = lastAssistant),
                completed,
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                logger = TestLogger,
                notifyParent = notifications::add,
            )

            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            assertEquals(
                listOf(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = firstAssistant),
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 1, item = lastAssistant),
                    completed,
                ),
                runtime.resume().toList(),
            )

            assertEquals(
                listOf(
                    StableCleanEvent.AgentMessage(
                        author = "/root/worker",
                        recipient = "/root",
                        content = listOf(
                            AgentMessageInputContent.InputText(
                                "Message Type: FINAL_ANSWER\n" +
                                    "Task name: /root\n" +
                                    "Sender: /root/worker\n" +
                                    "Payload:\nchild result",
                            ),
                        ),
                    ),
                ),
                notifications,
            )
        }

        test("a failed response event still reports the newest persisted assistant message") {
            val notifications = mutableListOf<StableCleanEvent.AgentMessage>()
            val assistant = assistantMessage("partial result")
            val failed = ResponsesStreamEvent.Failed(
                FailedResponse(ResponseError(message = "upstream disconnected")),
            )
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistant),
                failed,
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                logger = TestLogger,
                notifyParent = notifications::add,
            )

            state.injectHistory(
                listOf(
                    StableCleanEvent.AssistantMessage(
                        content = assistantMessage("previous turn").content,
                    ),
                ),
            )
            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            assertEquals(
                listOf(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistant),
                    failed,
                ),
                runtime.resume().toList(),
            )

            assertEquals(listOf(parentMessage("partial result")), notifications)
        }

        test("an incomplete response event still reports the newest persisted assistant message") {
            val notifications = mutableListOf<StableCleanEvent.AgentMessage>()
            val assistant = assistantMessage("partial result")
            val incomplete = ResponsesStreamEvent.Incomplete(IncompleteResponse())
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistant),
                incomplete,
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                logger = TestLogger,
                notifyParent = notifications::add,
            )

            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            assertEquals(
                listOf(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistant),
                    incomplete,
                ),
                runtime.resume().toList(),
            )

            assertEquals(listOf(parentMessage("partial result")), notifications)
        }

        test("a completed response with pending tools does not notify the parent") {
            val notifications = mutableListOf<StableCleanEvent.AgentMessage>()
            val call = ResponseItem.FunctionCall(
                name = "test_tool",
                arguments = "{}",
                callId = "call_1",
            )
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = call),
                ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                logger = TestLogger,
                notifyParent = notifications::add,
            )

            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            runtime.resume().toList()

            assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
            assertTrue(notifications.isEmpty())
        }

        test("a parent delivery failure does not fail the child turn") {
            val assistant = assistantMessage("child result")
            val completed = ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true))
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistant),
                completed,
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                logger = TestLogger,
                notifyParent = { error("parent unavailable") },
            )

            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            assertEquals(
                listOf(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistant),
                    completed,
                ),
                runtime.resume().toList(),
            )
        }

        test("an upstream runtime failure is rethrown without notifying the parent") {
            val notifications = mutableListOf<StableCleanEvent.AgentMessage>()
            val assistant = assistantMessage("partial result")
            val state = stateFor(
                flow = flow {
                    emit(ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistant))
                    error("network failure")
                },
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                logger = TestLogger,
                notifyParent = notifications::add,
            )

            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            val failure = assertFailsWith<IllegalStateException> {
                runtime.resume().toList()
            }

            assertEquals("network failure", failure.message)
            assertTrue(notifications.isEmpty())
        }

        test("a downstream collector failure is not reported as a child failure") {
            val notifications = mutableListOf<StableCleanEvent.AgentMessage>()
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistantMessage("child result")),
                ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                logger = TestLogger,
                notifyParent = notifications::add,
            )

            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            assertFailsWith<CollectorFailure> {
                runtime.resume().collect {
                    throw CollectorFailure()
                }
            }

            assertTrue(notifications.isEmpty())
        }
    }
}

private val TestLogger = KotlinLogging.logger {}

private class RequestRuntime(
    private val delegate: KodexAgentState,
) : ResumableAgentLayer, KodexAgentState by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = delegate.requestResponseApi()
}

private suspend fun CoroutineScope.stateFor(
    vararg events: ResponsesStreamEvent,
): KodexAgentState = stateFor(flowOf(*events))

private suspend fun CoroutineScope.stateFor(
    flow: Flow<ResponsesStreamEvent>,
): KodexAgentState = createKodexAgentState(
    client = mockOpenAiClient {
        createResponse { flow }
    },
    storage = InMemoryKodexAgentStorage(
        KodexAgentSettings(
            model = OpenAiModelId("test-model"),
            threadName = "/root/worker",
        ),
    ),
    contextSettings = TestAgentContextSettings,
    mcpService = TestMcpService(),
)

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private fun parentMessage(text: String): StableCleanEvent.AgentMessage =
    StableCleanEvent.AgentMessage(
        author = "/root/worker",
        recipient = "/root",
        content = listOf(
            AgentMessageInputContent.InputText(
                "Message Type: FINAL_ANSWER\n" +
                    "Task name: /root\n" +
                    "Sender: /root/worker\n" +
                    "Payload:\n$text",
            ),
        ),
    )

private class CollectorFailure : IllegalStateException()
