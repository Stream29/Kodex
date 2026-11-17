package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.cli.settings.KnownReasoningEfforts
import io.github.stream29.kodex.cli.settings.settingsLabel
import io.github.stream29.kodex.openai.*

@Composable
public actual fun NewSessionDefaultsContent(
    viewModel: NewSessionSettingsViewModel, renderer: SettingsRenderer,
) {
    val state = viewModel.state.collectAsState().value
    key(viewModel) { UiColumn {
        renderer.Section("Model behavior") {
            renderer.Choice(
                DefaultsChoiceField.Model.name, "Model",
                state.modelOptions.map { UiChoiceOption(it.value, it.value) }, state.settings.model.value,
                onSelect = { selected -> state.modelOptions.firstOrNull { it.value == selected }?.let {
                    viewModel.updateModel(state.revision, it)
                } }, enabled = state.active, supportingText = null,
            )
            renderer.Choice(
                DefaultsChoiceField.Reasoning.name, "Reasoning",
                KnownReasoningEfforts.map { UiChoiceOption(it.wireName, it.wireName) }, state.settings.reasoningEffort.wireName,
                onSelect = { selected -> KnownReasoningEfforts.firstOrNull { it.wireName == selected }?.let {
                    viewModel.updateReasoningEffort(state.revision, it)
                } }, enabled = state.active, supportingText = null,
            )
            renderer.Choice(
                DefaultsChoiceField.ServiceTier.name, "Service tier",
                ServiceTier.entries.map { UiChoiceOption(it.name, it.settingsLabel()) }, state.settings.serviceTier.name,
                onSelect = { selected -> ServiceTier.entries.firstOrNull { it.name == selected }?.let {
                    viewModel.updateServiceTier(state.revision, it)
                } }, enabled = state.active, supportingText = null,
            )
            renderer.Choice(
                DefaultsChoiceField.Questions.name, "Questions",
                RequestUserInputMode.entries.map { UiChoiceOption(it.name, it.settingsLabel()) }, state.settings.requestUserInputMode.name,
                onSelect = { selected -> RequestUserInputMode.entries.firstOrNull { it.name == selected }?.let {
                    viewModel.updateRequestUserInputMode(state.revision, it)
                } }, enabled = state.active, supportingText = "Controls whether the agent may pause to ask for input.",
            )
        }
    } }
}
