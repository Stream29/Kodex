package io.github.stream29.codex.lite.agentruntime.decorator.steer

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState as createCodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
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
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        assertEquals(CodexAgentStateValue.Empty, state.state.value)
        val pendingSteer = MutableStateFlow(userSteer("first"))
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
        }

        runtime.resume().toList()

        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
        assertEquals(listOf(listOf("first")), storage.userTextBatches())
        assertTrue(pendingSteer.value.isEmpty())
    }

    test("one resume delivers each pending input without rotating turn id") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        state.appendUserMessage(textContent("initial"))
        val turnId = storage.settings.latestValue().turnId
        val pendingSteer = MutableStateFlow(emptyList<StableCleanEvent.Steerable>())
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
        }

        pendingSteer.update { inputs -> inputs + userMessage("first") }
        pendingSteer.update { inputs -> inputs + userMessage("second") }
        assertEquals(
            listOf(
                userMessage("first"),
                userMessage("second"),
            ),
            pendingSteer.value,
        )

        runtime.resume().toList()
        assertEquals(
            listOf(listOf("initial"), listOf("first"), listOf("second")),
            storage.userTextBatches(),
        )
        assertEquals(
            listOf(stableUserMessage("first"), stableUserMessage("second")),
            storage.stable.indexes().toList().takeLast(2).map { index -> storage.stable[index] },
        )
        assertEquals(turnId, storage.settings.latestValue().turnId)
        assertTrue(pendingSteer.value.isEmpty())

        runtime.resume().toList()
        assertEquals(
            listOf(listOf("initial"), listOf("first"), listOf("second")),
            storage.userTextBatches(),
        )
        assertEquals(turnId, storage.settings.latestValue().turnId)
    }

    test("assistant message state accepts pending input") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        state.injectHistory(
            listOf(
                StableCleanEvent.AssistantMessage(
                    content = textContent("finished"),
                ),
            ),
        )
        assertEquals(CodexAgentStateValue.AssistantMessage, state.state.value)
        val pendingSteer = MutableStateFlow(userSteer("continue"))
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
        }

        runtime.resume().toList()

        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
        assertEquals(listOf(listOf("continue")), storage.userTextBatches())
        assertTrue(pendingSteer.value.isEmpty())
    }

    test("agent-message steer is persisted without becoming a user message") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val message = StableCleanEvent.AgentMessage(
            author = "/root/worker",
            recipient = "/root",
            content = listOf(
                AgentMessageInputContent.InputText(
                    "Message Type: FINAL_ANSWER\nTask name: /root\nSender: /root/worker\nPayload:\ndone",
                ),
            ),
        )
        val pendingSteer = MutableStateFlow(listOf<StableCleanEvent.Steerable>(message))
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
        }

        runtime.resume().toList()

        val index = storage.stable.indexes().toList().single()
        assertEquals(message, storage.stable[index])
        assertEquals(CodexAgentStateValue.UserMessage, state.state.value)
        assertTrue(pendingSteer.value.isEmpty())
    }

    test("clean user steer is persisted unchanged") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val message = StableCleanEvent.UserMessage(textContent("continue"))
        val pendingSteer = MutableStateFlow(listOf<StableCleanEvent.Steerable>(message))
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
        }

        runtime.resume().toList()

        val index = storage.stable.indexes().toList().single()
        assertEquals(message, storage.stable[index])
        assertTrue(pendingSteer.value.isEmpty())
    }

    test("pending input can follow a completed tool batch in the same turn") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val pending = PendingFunctionToolEvent(
            name = "test_tool",
            arguments = JsonObject(emptyMap()),
            callId = "call_1",
        )
        state.appendUserMessage(textContent("initial"))
        val turnId = storage.settings.latestValue().turnId
        state.setPending(pending)
        state.completeToolCall(
            StableTextToolEvent(
                callId = pending.callId,
                itemId = pending.itemId,
                name = pending.name,
                arguments = JsonObject(emptyMap()),
                result = "done",
            ),
        )
        assertEquals(CodexAgentStateValue.ToolCompleted, state.state.value)
        val pendingSteer = MutableStateFlow(userSteer("adjust the next step"))
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
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
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        state.appendUserMessage(textContent("initial"))
        val interruptingInput = userSteer("interrupt instead")
        val pendingSteer = MutableStateFlow(interruptingInput)
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
        }

        assertEquals(interruptingInput, pendingSteer.getAndUpdate { emptyList() })
        runtime.resume().toList()

        assertEquals(listOf(listOf("initial")), storage.userTextBatches())
    }

    test("pending tool state does not consume pending input") {
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = createCodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        state.appendUserMessage(textContent("initial"))
        state.setPending(
            PendingFunctionToolEvent(
                name = "test_tool",
                arguments = JsonObject(emptyMap()),
                callId = "call_1",
            ),
        )
        assertIs<CodexAgentStateValue.ToolPending>(state.state.value)
        val input = userSteer("wait until the tool finishes")
        val pendingSteer = MutableStateFlow(input)
        val runtime = TestRuntime(state).steerRuntime {
            pendingSteer.getAndUpdate { emptyList() }
        }

        runtime.resume().toList()

        assertIs<CodexAgentStateValue.ToolPending>(state.state.value)
        assertEquals(input, pendingSteer.value)
        assertEquals(listOf(listOf("initial")), storage.userTextBatches())
    }

    test("runtime delivery and interrupt retraction cannot claim the same steer") {
        repeat(100) { iteration ->
            val input = userSteer("race $iteration")
            val pendingSteer = MutableStateFlow(input)
            val steerProvider = SteerProvider {
                pendingSteer.getAndUpdate { emptyList() }
            }

            val claims = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val runtimeClaim = async {
                    start.await()
                    steerProvider.take()
                }
                val interruptClaim = async {
                    start.await()
                    pendingSteer.getAndUpdate { emptyList() }
                }
                start.complete(Unit)
                listOf(runtimeClaim.await(), interruptClaim.await())
            }

            assertEquals(listOf(input), claims.filter { it.isNotEmpty() })
            assertTrue(pendingSteer.value.isEmpty())
        }
    }
    }
}

private class TestRuntime(
    private val delegate: CodexAgentState,
) : ResumableAgentLayer, CodexAgentState by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {}
}

private fun textContent(text: String): List<ContentItem> =
    listOf(ContentItem.InputText(text))

private fun userSteer(text: String): List<StableCleanEvent.Steerable> =
    listOf(userMessage(text))

private fun userMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(textContent(text))

private fun stableUserMessage(text: String): StableCleanEvent.UserMessage =
    StableCleanEvent.UserMessage(textContent(text))

private suspend fun InMemoryCodexAgentStorage.userTextBatches(): List<List<String>> =
    stable.indexes().toList().mapNotNull { index ->
        val message = stable[index] as? StableCleanEvent.UserMessage ?: return@mapNotNull null
        message.content.filterIsInstance<ContentItem.InputText>().map(ContentItem.InputText::text)
    }.toList()

private suspend fun CodexAgentState.setPending(event: PendingToolEvent) {
    modify { storage ->
        storage.unstable[storage.latestIndex() + 1] = listOf(
            event,
        )
    }
}

private fun testSettings(): CodexAgentSettings =
    CodexAgentSettings(OpenAiModelId("test-model"))
