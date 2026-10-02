package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationDependencies
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.createRuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.CoroutineScope

/** Bind once to an exact Agent or draft. No active-session lookup or settings mirror is introduced. */
public fun createBoundRuntimeConfigurationViewModel(
    target: AgentSettingsViewModel,
    ownerScope: CoroutineScope,
): RuntimeConfigurationViewModel = createRuntimeConfigurationViewModel(
    object : RuntimeConfigurationDependencies {
        override val configuration = target.settings.projectState {
            RuntimeConfiguration(it.model, it.reasoning.effort, it.serviceTier, it.requestUserInputMode)
        }
        override val models = target.models
        override suspend fun updateModelConfiguration(
            model: OpenAiModelId,
            effort: ReasoningEffort,
            tier: ServiceTier,
        ) = target.updateModelConfiguration(model, effort, tier)
        override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) =
            target.updateRequestUserInputMode(mode)
    },
    ownerScope,
)
