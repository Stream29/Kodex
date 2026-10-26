package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.UiChoiceOption
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.openai.ReasoningEffort

@Composable
public actual fun SessionTitleSettingsContent(
    viewModel: SessionTitleSettingsViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    key(viewModel) { UiColumn {
        if (showOperationFailure && state.operationFailure) {
            UiText("A settings operation failed.", UiTextRole.Error)
            renderer.Action(label = "Dismiss", onClick = viewModel::dismissFailure, enabled = true, autoFocus = false)
        }
        renderer.Section(title = "Title generation") {
            renderer.Toggle(
                label = "Automatic session title", checked = state.enabled,
                onChange = viewModel::setEnabled,
                enabled = true, supportingText = null,
            )
            renderer.Choice(
                fieldKey = TitleChoiceField.Model.name, label = "Title model",
                options = state.modelOptions.map { UiChoiceOption(it.value, it.value) },
                selectedKey = state.effectiveModel.value,
                onSelect = { selected -> state.modelOptions.firstOrNull { it.value == selected }?.let(viewModel::setModel) },
                enabled = state.enabled,
                supportingText = if (state.enabled) null
                    else "Available when automatic session titles are enabled.",
            )
            renderer.Choice(
                fieldKey = TitleChoiceField.Reasoning.name, label = "Title reasoning",
                options = state.reasoningOptions.map { UiChoiceOption(it.wireName, it.titleLabel()) },
                selectedKey = state.reasoningEffort.wireName,
                onSelect = { selected -> state.reasoningOptions.firstOrNull { it.wireName == selected }?.let(viewModel::setReasoningEffort) },
                enabled = state.enabled,
                supportingText = "Reasoning effort used to generate automatic session titles.",
            )
        }
    } }
}

private fun ReasoningEffort.titleLabel(): String = when (this) {
    ReasoningEffort.None -> "none"
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"
    is ReasoningEffort.Custom -> wireName
}
