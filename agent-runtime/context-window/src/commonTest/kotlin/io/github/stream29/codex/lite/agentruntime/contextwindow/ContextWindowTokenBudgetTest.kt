package io.github.stream29.codex.lite.agentruntime.contextwindow

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestContextPrefixProvider
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun testCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
        codexCliStorage = CodexCliStorage(Path(".codex-lite-test-model-catalog")),
    )

private suspend fun testState(storage: MutableCodexAgentStorage) =
    CodexAgentState(
        client = mockOpenAiClient(),
        storage = storage,
        contextPrefixProvider = TestContextPrefixProvider,
        toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
    )

val contextWindowTokenBudgetTest by testSuite {
    test("uses one storage snapshot to calculate remaining budget") {
        val storage = InMemoryCodexAgentStorage(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 800L,
            ),
        )
        storage.tokenCount[0] = 760L

        assertEquals(40L, testState(storage).tokensUntilCompaction(testCatalog()))
    }

    test("reports an unknown budget before OpenAI reports token usage") {
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))

        assertNull(testState(storage).tokensUntilCompaction(testCatalog()))
    }
}
