package io.github.stream29.codex.lite.agentstate.contextwindow

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
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

private suspend fun CoroutineScope.testState(storage: MutableCodexAgentStorage) =
    CodexAgentState(
        client = mockOpenAiClient(),
        storage = storage,
        contextSettings = TestAgentContextSettings,
        mcpService = TestMcpService(),
    )

val contextWindowTokenBudgetTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
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
}
