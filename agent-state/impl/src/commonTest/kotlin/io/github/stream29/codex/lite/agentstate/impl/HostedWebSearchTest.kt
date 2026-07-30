package io.github.stream29.codex.lite.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.WebSearchAction
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertIs

val hostedWebSearchTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("hosted web search response items stay in history without a local tool runtime") {
        val webSearchCall = ResponseItem.WebSearchCall(
            status = "completed",
            action = WebSearchAction.Search(query = "current weather"),
        )
        val assistantMessage = ResponseItem.Message(
            role = MessageRole.Assistant,
            content = listOf(ContentItem.OutputText("The hosted search completed.")),
        )
        val requests = mutableListOf<ResponsesApiRequest>()
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
            ),
        )
        val agent = CodexAgentState(
            client = mockOpenAiClient {
                createResponse { request ->
                    requests += request
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, webSearchCall),
                        ResponsesStreamEvent.OutputItemDone(1, assistantMessage),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                    )
                }
            },
            storage = storage,
        )

        agent.appendUserMessage(listOf(ContentItem.InputText("Find current weather.")))
        agent.requestResponseApi().toList()

        assertEquals(1, requests.size)
        assertEquals(StableCleanEvent.WebSearchCall(webSearchCall), storage.stable[2])
        assertEquals(
            StableCleanEvent.AssistantMessage(assistantMessage.content),
            storage.stable[3],
        )
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertIs<StableCleanEvent.WebSearchCall>(storage.stable[2])
    }
    }
}
