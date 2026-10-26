package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.openai.*

@Composable
public actual fun NewSessionDefaultsContent(
    viewModel: NewSessionSettingsViewModel, renderer: SettingsRenderer, showOperationFailure: Boolean,
) {
    val state = viewModel.state.collectAsState().value
    val failed by viewModel.operationFailure.collectAsState()
    key(viewModel) { UiColumn {
        if (showOperationFailure && failed) {
            UiText("A settings operation failed.", UiTextRole.Error)
            renderer.Action("Dismiss", enabled = state.active, onClick = viewModel::dismissOperationFailure)
        }
        renderer.Section("Model behavior") {
            renderer.Choice(
                DefaultsChoiceField.Model.name, "Model",
                state.modelOptions.map { UiChoiceOption(it.value, it.value) }, state.settings.model.value,
                onSelect = { selected -> state.modelOptions.firstOrNull { it.value == selected }?.let {
                    viewModel.updateModel(state.revision, it)
                } }, enabled = state.active,
            )
            renderer.Choice(
                DefaultsChoiceField.Reasoning.name, "Reasoning",
                knownEfforts.map { UiChoiceOption(it.wireName, it.wireName) }, state.settings.reasoningEffort.wireName,
                onSelect = { selected -> knownEfforts.firstOrNull { it.wireName == selected }?.let {
                    viewModel.updateReasoningEffort(state.revision, it)
                } }, enabled = state.active,
            )
            renderer.Choice(
                DefaultsChoiceField.ServiceTier.name, "Service tier",
                ServiceTier.entries.map { UiChoiceOption(it.name, it.label()) }, state.settings.serviceTier.name,
                onSelect = { selected -> ServiceTier.entries.firstOrNull { it.name == selected }?.let {
                    viewModel.updateServiceTier(state.revision, it)
                } }, enabled = state.active,
            )
            renderer.Choice(
                DefaultsChoiceField.Questions.name, "Questions",
                RequestUserInputMode.entries.map { UiChoiceOption(it.name, it.label()) }, state.settings.requestUserInputMode.name,
                onSelect = { selected -> RequestUserInputMode.entries.firstOrNull { it.name == selected }?.let {
                    viewModel.updateRequestUserInputMode(state.revision, it)
                } }, enabled = state.active, supportingText = "Controls whether the agent may pause to ask for input.",
            )
        }
    } }
}

private val knownEfforts = listOf(
    ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium,
    ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max,
)

private fun ServiceTier.label(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
    ServiceTier.Ultrafast -> "ultrafast"
}

private fun RequestUserInputMode.label(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}
