package io.github.stream29.codex.lite.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.WebSearchAction
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val hostedWebSearchContextPrefixProvider: AgentContextPrefixProvider =
    object : AgentContextPrefixProvider {
        override val environmentContext: EnvironmentContext =
            EnvironmentContext(
                environments = emptyList(),
                currentDate = LocalDate(2026, 7, 18),
                timeZone = TimeZone.UTC,
            )

        override val availableSkills: List<AvailableSkill> = emptyList()

        override val agentMd: List<AgentsMdInstruction> = emptyList()
    }

val hostedWebSearchTest by testSuite {
    test("hosted web search stays in the Responses stream without a local tool runtime") {
        val tool = ToolSpec.WebSearch(
            externalWebAccess = true,
            indexedWebAccess = true,
        )
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
                tools = listOf(tool),
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
            contextPrefixProvider = hostedWebSearchContextPrefixProvider,
        )

        agent.appendUserMessage(listOf(ContentItem.InputText("Find current weather.")))
        agent.requestResponseApi().toList()

        assertEquals(listOf(tool), requests.single().tools)
        assertEquals(webSearchCall, storage.history[2])
        assertEquals(assistantMessage, storage.history[3])
        assertEquals(CodexAgentStateValue.AssistantMessage, agent.state.value)
        assertIs<ResponseItem.WebSearchCall>(storage.history[2])
    }
}
