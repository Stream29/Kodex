package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationModelOption
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationState
import io.github.stream29.kodex.cli.runtimeconfiguration.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.launch

@Composable
public actual fun SuggestSubagentTaskContent(
    viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending,
    renderer: SuggestionRenderer, onBrowseWorkingDirectory: (String) -> Unit,
) {
    key(viewModel, state.callId) {
        val scope = rememberCoroutineScope()
        SuggestionConfigurationMenuContent(viewModel, state) { menu ->
            renderer.Frame(state.callId) { controls ->
                fun submit(accepted: Boolean) {
                    scope.launch { viewModel.submit(state.callId, state.revision, accepted) }
                }
                controls.Header("Suggested Sessions")
                state.arguments.tasks.forEach { controls.Task(it.name, it.prompt) }
                controls.Configuration(menu, state.configuration.cwd, !state.submitting) { onBrowseWorkingDirectory(state.callId) }
                controls.Decision("Accept", "Create the suggested Sessions.", false, !state.submitting) { submit(true) }
                controls.Decision("Reject", "Decline, optionally with a message.", state.rejecting, !state.submitting) {
                    if (viewModel.setRejecting(state.callId, true)) controls.focusFeedback()
                }
                if (state.rejecting) {
                    controls.Feedback(
                        state.feedback, !state.submitting,
                        onChange = { viewModel.updateFeedback(state.callId, it) }, onSubmitted = { submit(false) },
                    )
                    controls.RejectSubmit(!state.submitting) { submit(false) }
                }
            }
        }
    }
}

@Composable
public actual fun SuggestionConfigurationMenuContent(
    viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending,
    content: @Composable (RuntimeConfigurationMenu) -> Unit,
) {
    val models = viewModel.models.collectAsState().value
    val current = state.configuration
    val options = suggestionModelOptions(models, current).map { model ->
        val info = models.firstOrNull { it.slug == model }
        RuntimeConfigurationModelOption(
            model, info?.supportedReasoningLevels?.map { it.effort }.orEmpty().ifEmpty { listOf(current.reasoningEffort) },
            info?.availableServiceTiers().orEmpty().ifEmpty { listOf(ServiceTier.Default) },
        )
    }
    val projection = RuntimeConfigurationState(
        RuntimeConfiguration(current.model, current.reasoningEffort, current.serviceTier, current.requestUserInputMode), options,
    )
    val menu = checkNotNull(runtimeConfigurationMenu(
        projection,
        onConfigurationSelected = { model, effort, tier -> viewModel.updateModelConfiguration(state.callId, model, effort, tier) },
        onRequestUserInputModeSelected = { viewModel.updateRequestUserInputMode(state.callId, it) },
    ))
    content(menu)
}

public actual fun suggestionModelOptions(models: List<ModelInfo>, configuration: SuggestedSessionConfiguration): List<OpenAiModelId> =
    (models.map(ModelInfo::slug) + configuration.model).distinct()
