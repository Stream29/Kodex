package io.github.stream29.kodex.agentstate.contextwindow

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun testCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
    )

private suspend fun CoroutineScope.testState(storage: MutableKodexAgentStorage) =
    KodexAgentState(
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
        val storage = InMemoryKodexAgentStorage(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                autoCompactionTokenLimit = 800L,
            ),
        )
        storage.tokenCount[0] = 760L

        assertEquals(40L, testState(storage).tokensUntilCompaction(testCatalog()))
    }

    test("reports an unknown budget before OpenAI reports token usage") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))

        assertNull(testState(storage).tokensUntilCompaction(testCatalog()))
    }
    }
}
