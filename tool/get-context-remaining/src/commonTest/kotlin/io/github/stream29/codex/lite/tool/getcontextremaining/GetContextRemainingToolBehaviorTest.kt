package io.github.stream29.codex.lite.tool.getcontextremaining

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.io.files.Path
import kotlin.test.assertEquals

private fun getContextRemainingTestCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
        codexCliStorage = CodexCliStorage(Path(".codex-lite-test-model-catalog")),
    )

val getContextRemainingToolTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("reads the same current budget used by the compaction runtime") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 800L,
            ),
        )
        storage.tokenCount[0] = 760L
        val state = CodexAgentState(
            client = mockOpenAiClient(),
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        val catalog = getContextRemainingTestCatalog()
        val output = state.getContextRemainingTool(catalog).handle(
            ResponseItem.FunctionCall(
                name = GetContextRemainingTools.Name,
                arguments = "{}",
                callId = "call_context",
            ),
        ) as ResponseItem.FunctionCallOutput

        assertEquals(
            "You have 40 tokens left in this context window.",
            (output.output.body as FunctionCallOutputBody.Text).text,
        )
    }
    }
}
