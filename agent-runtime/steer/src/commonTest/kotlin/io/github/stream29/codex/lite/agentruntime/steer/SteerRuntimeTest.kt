package io.github.stream29.codex.lite.agentruntime.steer

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState as createCodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestContextPrefixProvider
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

val steerRuntimeTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("empty state accepts the first pending input") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        assertEquals(CodexAgentStateValue.Empty, state.state.value)
        val pendingSteer = MutableStateFlow<List<ContentItem>?>(textContent("first"))
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { null }
        }

        runtime.resume().toList()

        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
        assertEquals(listOf(listOf("first")), storage.userTextBatches())
        assertNull(pendingSteer.value)
    }

    test("one resume delivers merged pending input without rotating turn id") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        state.appendUserMessage(textContent("initial"))
        val turnId = storage.settings.latestValue().turnId
        val pendingSteer = MutableStateFlow<List<ContentItem>?>(null)
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { null }
        }

        pendingSteer.update { content -> content.orEmpty() + textContent("first") }
        pendingSteer.update { content -> content.orEmpty() + textContent("second") }
        assertEquals(
            textContent("first") + textContent("second"),
            pendingSteer.value,
        )

        runtime.resume().toList()
        assertEquals(
            listOf(listOf("initial"), listOf("first", "second")),
            storage.userTextBatches(),
        )
        assertEquals(turnId, storage.settings.latestValue().turnId)
        assertNull(pendingSteer.value)

        runtime.resume().toList()
        assertEquals(
            listOf(listOf("initial"), listOf("first", "second")),
            storage.userTextBatches(),
        )
        assertEquals(turnId, storage.settings.latestValue().turnId)
    }

    test("assistant message state accepts pending input") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        state.injectHistory(
            listOf(
                ResponseItem.Message(
                    role = MessageRole.Assistant,
                    content = textContent("finished"),
                ),
            ),
        )
        assertEquals(CodexAgentStateValue.AssistantMessage, state.state.value)
        val pendingSteer = MutableStateFlow<List<ContentItem>?>(textContent("continue"))
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { null }
        }

        runtime.resume().toList()

        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
        assertEquals(listOf(listOf("continue")), storage.userTextBatches())
        assertNull(pendingSteer.value)
    }

    test("pending input can follow a completed tool batch in the same turn") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val call = ResponseItem.FunctionCall(
            name = "test_tool",
            arguments = "{}",
            callId = "call_1",
        )
        state.appendUserMessage(textContent("initial"))
        val turnId = storage.settings.latestValue().turnId
        state.injectHistory(listOf(call))
        state.completeToolCall(
            ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = FunctionCallOutputPayload.fromText("done"),
            ),
        )
        assertEquals(CodexAgentStateValue.ToolCompleted, state.state.value)
        val pendingSteer = MutableStateFlow<List<ContentItem>?>(
            textContent("adjust the next step"),
        )
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { null }
        }

        runtime.resume().toList()

        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
        assertEquals(
            listOf(listOf("initial"), listOf("adjust the next step")),
            storage.userTextBatches(),
        )
        assertEquals(turnId, storage.settings.latestValue().turnId)
    }

    test("frontend can retract pending steer before runtime delivery") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        state.appendUserMessage(textContent("initial"))
        val interruptingInput = textContent("interrupt instead")
        val pendingSteer = MutableStateFlow<List<ContentItem>?>(interruptingInput)
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { null }
        }

        assertEquals(interruptingInput, pendingSteer.getAndUpdate { null })
        runtime.resume().toList()

        assertEquals(listOf(listOf("initial")), storage.userTextBatches())
    }

    test("pending tool state does not consume pending input") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        state.appendUserMessage(textContent("initial"))
        state.injectHistory(
            listOf(
                ResponseItem.FunctionCall(
                    name = "test_tool",
                    arguments = "{}",
                    callId = "call_1",
                ),
            ),
        )
        assertIs<CodexAgentStateValue.ToolPending>(state.state.value)
        val input = textContent("wait until the tool finishes")
        val pendingSteer = MutableStateFlow<List<ContentItem>?>(input)
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { null }
        }

        runtime.resume().toList()

        assertIs<CodexAgentStateValue.ToolPending>(state.state.value)
        assertEquals(input, pendingSteer.value)
        assertEquals(listOf(listOf("initial")), storage.userTextBatches())
    }

    test("runtime delivery and interrupt retraction cannot claim the same steer") {
        repeat(100) { iteration ->
            val input = textContent("race $iteration")
            val pendingSteer = MutableStateFlow<List<ContentItem>?>(input)
            val steerProvider = SteerProvider {
                pendingSteer.getAndUpdate { null }
            }

            val claims = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val runtimeClaim = async {
                    start.await()
                    steerProvider.take()
                }
                val interruptClaim = async {
                    start.await()
                    pendingSteer.getAndUpdate { null }
                }
                start.complete(Unit)
                listOf(runtimeClaim.await(), interruptClaim.await())
            }

            assertEquals(listOf(input), claims.filterNotNull())
            assertNull(pendingSteer.value)
        }
    }
    }
}

private class TestRuntime(
    private val delegate: CodexAgentState,
) : CodexAgentRuntime, CodexAgentState by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {}
}

private fun textContent(text: String): List<ContentItem> =
    listOf(ContentItem.InputText(text))

private suspend fun InMemoryCodexAgentStorage.userTextBatches(): List<List<String>> =
    history.indexes().toList().mapNotNull { index ->
        val message = history[index] as? ResponseItem.Message
        if (message?.role != MessageRole.User) {
            null
        } else {
            message.content.filterIsInstance<ContentItem.InputText>().map(ContentItem.InputText::text)
        }
    }.toList()

private fun testSettings(): CodexAgentSettings =
    CodexAgentSettings(OpenAiModelId("test-model"))
