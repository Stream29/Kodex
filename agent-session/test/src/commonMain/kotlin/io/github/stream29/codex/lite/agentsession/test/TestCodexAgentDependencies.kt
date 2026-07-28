package io.github.stream29.codex.lite.agentsession.test

import io.github.stream29.codex.lite.agentsession.composition.CodexAgentDependencies
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.hook.contract.NoOpCodexHooks
import io.github.stream29.codex.lite.openai.ModelInfo
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dependencies for session tests that do not execute external services. */
public fun testCodexAgentDependencies(
    client: OpenAiClient = mockOpenAiClient(),
): CodexAgentDependencies {
    val model = ModelInfo(
        slug = OpenAiModelId(TestModel),
        displayName = "Test Model",
        contextWindow = 100_000L,
        maxContextWindow = 100_000L,
    )
    return CodexAgentDependencies(
        client = client,
        modelCatalog = TestModelCatalog(model),
        contextSettings = TestAgentContextSettings,
        shellSettings = TestAgentContextSettings,
        mcpService = TestMcpService(),
        hooks = NoOpCodexHooks,
    )
}

private class TestModelCatalog(
    model: ModelInfo,
) : OpenAiModelCatalog {
    override val models: StateFlow<List<ModelInfo>> =
        MutableStateFlow(listOf(model))

    override suspend fun refresh(): List<ModelInfo> =
        models.value

    override fun resolve(model: OpenAiModelId): ModelInfo =
        models.value.first().copy(slug = model)

    override fun close(): Unit = Unit
}

private const val TestModel: String = "test-model"
