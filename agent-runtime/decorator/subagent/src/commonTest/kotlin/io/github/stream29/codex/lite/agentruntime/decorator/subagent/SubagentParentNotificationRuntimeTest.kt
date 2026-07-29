package io.github.stream29.codex.lite.agentruntime.decorator.subagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState as createCodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FailedResponse
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseError
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
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
            val notifications = mutableListOf<ResponseItem.AgentMessage>()
            val firstAssistant = assistantMessage("intermediate result")
            val lastAssistant = assistantMessage("child result")
            val completed = ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true))
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = firstAssistant),
                ResponsesStreamEvent.OutputItemDone(outputIndex = 1, item = lastAssistant),
                completed,
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
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
                    ResponseItem.AgentMessage(
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

        test("failed response does not notify the parent") {
            val notifications = mutableListOf<ResponseItem.AgentMessage>()
            val failed = ResponsesStreamEvent.Failed(
                FailedResponse(ResponseError(message = "upstream disconnected")),
            )
            val state = stateFor(failed)
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
                notifyParent = notifications::add,
            )

            state.injectHistory(listOf(assistantMessage("previous turn")))
            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            assertEquals(listOf(failed), runtime.resume().toList())

            assertTrue(notifications.isEmpty())
        }

        test("a completed response with pending tools does not notify the parent") {
            val notifications = mutableListOf<ResponseItem.AgentMessage>()
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
                notifyParent = notifications::add,
            )

            runtime.appendUserMessage(listOf(ContentItem.InputText("do the work")))
            runtime.resume().toList()

            assertIs<CodexAgentStateValue.ToolPending>(state.state.value)
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
            val notifications = mutableListOf<ResponseItem.AgentMessage>()
            val state = stateFor(flow = flow { error("network failure") })
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
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
            val notifications = mutableListOf<ResponseItem.AgentMessage>()
            val state = stateFor(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistantMessage("child result")),
                ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
            )
            val runtime = RequestRuntime(state).subagentParentNotificationRuntime(
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

private class RequestRuntime(
    private val delegate: CodexAgentState,
) : ResumableAgentLayer, CodexAgentState by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = delegate.requestResponseApi()
}

private suspend fun CoroutineScope.stateFor(
    vararg events: ResponsesStreamEvent,
): CodexAgentState = stateFor(flowOf(*events))

private suspend fun CoroutineScope.stateFor(
    flow: Flow<ResponsesStreamEvent>,
): CodexAgentState = createCodexAgentState(
    client = mockOpenAiClient {
        createResponse { flow }
    },
    storage = InMemoryCodexAgentStorage(
        CodexAgentSettings(
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

private fun ResponseItem.AgentMessage.inputText(): String =
    content.filterIsInstance<AgentMessageInputContent.InputText>()
        .joinToString(separator = "", transform = AgentMessageInputContent.InputText::text)

private class CollectorFailure : IllegalStateException()
