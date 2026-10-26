package io.github.stream29.kodex.cli.runtimeconfiguration

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationState
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.launch

@Composable
public actual fun RuntimeConfigurationContent(
    viewModel: RuntimeConfigurationViewModel, renderer: RuntimeConfigurationRenderer, enabled: Boolean,
) {
    RuntimeConfigurationMenuContent(viewModel) { renderer.Fields(it, enabled) }
}

@Composable
public actual fun RuntimeConfigurationMenuContent(
    viewModel: RuntimeConfigurationViewModel, content: @Composable (RuntimeConfigurationMenu) -> Unit,
) {
    key(viewModel) {
        val state = viewModel.state.collectAsState().value
        val scope = rememberCoroutineScope()
        runtimeConfigurationMenu(
            state,
            onConfigurationSelected = { model, effort, tier ->
                scope.launch { viewModel.updateModelConfiguration(model, effort, tier) }
            },
            onRequestUserInputModeSelected = { mode ->
                scope.launch { viewModel.updateRequestUserInputMode(mode) }
            },
        )?.let { content(it) }
    }
}

public actual fun runtimeConfigurationMenu(
    state: RuntimeConfigurationState,
    onConfigurationSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
    onRequestUserInputModeSelected: (RequestUserInputMode) -> Unit,
): RuntimeConfigurationMenu? {
    if (state.closed) return null
    val configuration = state.configuration
    return RuntimeConfigurationMenu(
        modelLabel = runtimeConfigurationLabel(configuration.model, configuration.reasoning, configuration.tier),
        models = state.modelOptions.map { option ->
            val model = option.model
            RuntimeMenuEntry.Branch(
                key = model, label = model.value, selected = model == configuration.model,
                initialChildKey = configuration.reasoning
                    .takeIf { model == configuration.model && it in option.efforts } ?: option.efforts.first(),
                children = {
                    option.efforts.map { effort ->
                        RuntimeMenuEntry.Branch(
                            key = effort, label = effort.wireName,
                            selected = model == configuration.model && effort == configuration.reasoning,
                            initialChildKey = configuration.tier.takeIf {
                                model == configuration.model && effort == configuration.reasoning && it in option.tiers
                            } ?: ServiceTier.Default,
                            children = {
                                option.tiers.map { tier ->
                                    RuntimeMenuEntry.Command(
                                        key = tier, label = tier.label(),
                                        selected = model == configuration.model && effort == configuration.reasoning && tier == configuration.tier,
                                        onSelect = { onConfigurationSelected(model, effort, tier) },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        },
        modeLabel = runtimeRequestUserInputModeLabel(configuration.requestUserInputMode),
        modes = RequestUserInputMode.entries.map { mode ->
            RuntimeMenuEntry.Command(
                key = mode, label = runtimeRequestUserInputModeLabel(mode), selected = mode == configuration.requestUserInputMode,
                onSelect = { onRequestUserInputModeSelected(mode) },
            )
        },
    )
}

public actual fun runtimeConfigurationLabel(model: OpenAiModelId, reasoning: ReasoningEffort, tier: ServiceTier): String =
    "${model.value} ${reasoning.wireName}" + if (tier == ServiceTier.Default) "" else " ${tier.label()}"

public actual fun runtimeRequestUserInputModeLabel(mode: RequestUserInputMode): String = when (mode) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

private fun ServiceTier.label(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
    ServiceTier.Ultrafast -> "ultrafast"
}
