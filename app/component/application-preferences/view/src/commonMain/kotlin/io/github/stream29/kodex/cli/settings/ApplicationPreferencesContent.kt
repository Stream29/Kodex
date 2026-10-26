package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.UiChoiceOption
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole

@Composable
public actual fun ApplicationPreferencesContent(
    viewModel: ApplicationPreferencesViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    key(viewModel) { UiColumn {
        if (showOperationFailure && state.operationFailure) {
            UiText("A settings operation failed.", UiTextRole.Error)
            renderer.Action(label = "Dismiss", onClick = viewModel::dismissFailure)
        }
        renderer.Section(title = "Sidebars") {
            PreferencesWidthItem(renderer, "Left sidebar width", state.leftWidth, viewModel::setLeftWidth)
            PreferencesWidthItem(renderer, "Right sidebar width", state.rightWidth, viewModel::setRightWidth)
        }
        renderer.Section(title = "Input") {
            renderer.Choice(
                fieldKey = PreferencesChoiceField.NewLineKey.name, label = "New line key",
                options = NewLineKey.entries.map { UiChoiceOption(it.name, it.preferencesLabel()) },
                selectedKey = state.newLineKey.name,
                onSelect = { selected -> NewLineKey.entries.firstOrNull { it.name == selected }?.let(viewModel::setNewLineKey) },
            )
            renderer.Choice(
                fieldKey = PreferencesChoiceField.SubmitKey.name, label = "Submit key",
                options = SubmitKey.entries.map { UiChoiceOption(it.name, it.preferencesLabel()) },
                selectedKey = state.submitKey.name,
                onSelect = { selected -> SubmitKey.entries.firstOrNull { it.name == selected }?.let(viewModel::setSubmitKey) },
            )
        }
    } }
}

@Composable
private fun PreferencesWidthItem(renderer: SettingsRenderer, label: String, columns: Int, onChange: (Int) -> Unit) {
    renderer.Item(label = label, supportingText = "$columns columns") {
        renderer.Action(
            label = "-", enabled = columns > MinimumSidebarWidthColumns,
            onClick = { onChange(columns - 1) },
        )
        renderer.Action(
            label = "+", enabled = columns < Int.MAX_VALUE,
            onClick = { onChange(columns + 1) },
        )
    }
}

private fun NewLineKey.preferencesLabel(): String = when (this) {
    NewLineKey.ShiftEnter -> "Shift+Enter"
    NewLineKey.Enter -> "Enter"
}
private fun SubmitKey.preferencesLabel(): String = when (this) {
    SubmitKey.Enter -> "Enter"
    SubmitKey.CtrlEnter -> "Ctrl+Enter"
}
