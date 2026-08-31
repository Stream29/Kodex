package io.github.stream29.kodex.tool.getcontextremaining

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolEvent
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.tool.builder.ToolBuilderJson
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun getContextRemainingTestCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
    )

val getContextRemainingToolTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("reads the same current budget used by the compaction runtime") {
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 800L,
            ),
        )
        storage.tokenCount[0] = 760L
        val state = KodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val catalog = getContextRemainingTestCatalog()
        val completed = assertIs<StableTextToolEvent>(
            state.getContextRemainingTool(catalog).handle(
                PendingFunctionToolEvent(
                name = GetContextRemainingTools.Name,
                arguments = ToolBuilderJson.parseToJsonElement("{}"),
                callId = "call_context",
            ),
            ),
        )

        assertEquals(
            "You have 40 tokens left in this context window.",
            completed.result,
        )
    }
    }
}
