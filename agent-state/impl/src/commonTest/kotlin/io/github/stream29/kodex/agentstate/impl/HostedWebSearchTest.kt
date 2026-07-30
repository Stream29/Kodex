package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.WebSearchAction
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
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
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
            ),
        )
        val agent = KodexAgentState(
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
        assertEquals(KodexAgentStateValue.AssistantMessage, agent.state.value)
        assertIs<StableCleanEvent.WebSearchCall>(storage.stable[2])
    }
    }
}
