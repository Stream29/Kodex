package io.github.stream29.codex.lite.agentruntime.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentEnvironment
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
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
import io.github.stream29.codex.lite.tool.getcontextremaining.GetContextRemainingTools
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlin.test.assertEquals

private val getContextRemainingTestPrefixProvider: AgentContextPrefixProvider =
    object : AgentContextPrefixProvider {
        override val environmentContext: EnvironmentContext =
            EnvironmentContext(
                environments = listOf(
                    AgentEnvironment(
                        id = "test",
                        cwd = Path("/workspace"),
                        shell = "bash",
                    ),
                ),
                currentDate = LocalDate(2026, 7, 18),
                timeZone = TimeZone.UTC,
            )

        override val availableSkills: List<AvailableSkill> = emptyList()

        override val agentMd: List<AgentsMdInstruction> = emptyList()
    }

private fun getContextRemainingTestCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
        codexCliStorage = CodexCliStorage(Path(".codex-lite-test-model-catalog")),
    )

val getContextRemainingToolTest by testSuite {
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
            contextPrefixProvider = getContextRemainingTestPrefixProvider,
        )
        val catalog = getContextRemainingTestCatalog()
        val runtime = state.compactionRuntime(catalog)

        val output = runtime.getContextRemainingTool(catalog).handle(
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
