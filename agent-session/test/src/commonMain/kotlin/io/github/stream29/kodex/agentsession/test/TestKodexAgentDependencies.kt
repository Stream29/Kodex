package io.github.stream29.kodex.agentsession.test

import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstate.test.TestMcpService
import io.github.stream29.kodex.hook.contract.NoOpKodexHooks
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dependencies for session tests that do not execute external services. */
public fun testKodexAgentDependencies(
    client: OpenAiClient = mockOpenAiClient(),
): KodexAgentDependencies {
    val model = ModelInfo(
        slug = OpenAiModelId(TestModel),
        displayName = "Test Model",
        contextWindow = 100_000L,
        maxContextWindow = 100_000L,
    )
    return KodexAgentDependencies(
        client = client,
        modelCatalog = TestModelCatalog(model),
        contextSettings = TestAgentContextSettings,
        shellSettings = TestAgentContextSettings,
        mcpService = TestMcpService(),
        hooks = NoOpKodexHooks,
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
