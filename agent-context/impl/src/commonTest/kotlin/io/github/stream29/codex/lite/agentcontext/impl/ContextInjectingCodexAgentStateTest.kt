package io.github.stream29.codex.lite.agentcontext.impl

import io.github.stream29.codex.lite.agentcontext.contract.AgentContextProvider
import io.github.stream29.codex.lite.agentcontext.contract.AgentContextSnapshot
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState as createCodexAgentState
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextInjectingCodexAgentStateTest {
    @Test
    fun agentMdPrecedesUserMessageAndIsIncludedInTheRequest() = runTest {
        val storage = InMemoryCodexAgentStorage(settings())
        val requests = mutableListOf<ResponsesApiRequest>()
        val delegate = createCodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(ResponsesStreamEvent.Completed(Response(id = "response")))
                }
            },
            storage = storage,
        )
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val provider = object : AgentContextProvider {
            override suspend fun provideAgentMd(snapshot: AgentContextSnapshot): String {
                snapshots += snapshot
                return "agent instructions"
            }
        }
        val state = delegate.withContextInjection(provider)

        val userIndex = state.appendUserMessage(listOf(ContentItem.InputText("hello")))
        state.requestResponseApi().toList()

        assertEquals(2, userIndex)
        assertEquals(userMessage("agent instructions"), storage.history[1])
        assertEquals(userMessage("hello"), storage.history[2])
        assertEquals(
            listOf(userMessage("agent instructions"), userMessage("hello")),
            requests.single().input,
        )
        assertEquals(listOf(CodexAgentStateValue.Empty), snapshots.map(AgentContextSnapshot::state))
        assertEquals(listOf(0), snapshots.map(AgentContextSnapshot::latestIndex))
    }

    @Test
    fun agentMdIsReinjectedAfterCompaction() = runTest {
        val storage = InMemoryCodexAgentStorage(settings())
        val delegate = createCodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response {
                    RemoteCompactionV2Response(
                        compactionOutput = ResponseItem.Compaction(encryptedContent = "compacted"),
                        completedResponse = null,
                    )
                }
            },
            storage = storage,
        )
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val provider = object : AgentContextProvider {
            override suspend fun provideAgentMd(snapshot: AgentContextSnapshot): String {
                snapshots += snapshot
                return "agent instructions ${snapshots.size}"
            }
        }
        val state = delegate.withContextInjection(provider)

        state.appendUserMessage(listOf(ContentItem.InputText("hello")))
        val compactionIndex = state.compact(
            trigger = RemoteCompactionV2Trigger.Auto,
            reason = RemoteCompactionV2Reason.ContextLimit,
            phase = RemoteCompactionV2Phase.PreTurn,
        )

        assertEquals(2, snapshots.size)
        assertEquals(0, snapshots[0].latestIndex)
        assertEquals(compactionIndex, snapshots[1].latestIndex)
        assertEquals(
            userMessage("agent instructions 2"),
            storage.history[state.latestIndex.value],
        )
        assertEquals(compactionIndex + 1, state.latestIndex.value)
    }
}

private fun settings(): CodexAgentSettings =
    CodexAgentSettings(OpenAiModelId("test-model"))

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )
