package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState

@Composable
public actual fun rememberApplicationPreferencesDropdowns(): ApplicationPreferencesDropdowns =
    ApplicationPreferencesDropdowns(rememberTuiDropdownState(), rememberTuiDropdownState())

@Composable
public actual fun BoxScope.ApplicationPreferencesComponent(
    viewModel: ApplicationPreferencesViewModel,
    showOperationFailure: Boolean,
) {
    val dropdowns = rememberApplicationPreferencesDropdowns()
    ApplicationPreferencesPanel(viewModel, dropdowns, showOperationFailure)
    ApplicationPreferencesDropdownMenus(viewModel, dropdowns)
}

@Composable
public actual fun ApplicationPreferencesPanel(
    viewModel: ApplicationPreferencesViewModel,
    dropdowns: ApplicationPreferencesDropdowns,
    showOperationFailure: Boolean,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    Column {
        if (showOperationFailure && state.operationFailure) {
            SettingsErrorText("A settings operation failed.")
            SettingsActionButton(label = "Dismiss", onClick = viewModel::dismissFailure)
        }
        SettingsSection(title = "Sidebars") {
            PreferencesWidthItem("Left sidebar width", state.leftWidth, viewModel::setLeftWidth)
            PreferencesWidthItem("Right sidebar width", state.rightWidth, viewModel::setRightWidth)
        }
        SettingsSection(title = "Input") {
            SettingsDropdownField(
                label = "New line key", selectedLabel = state.newLineKey.preferencesLabel(),
                dropdownState = dropdowns.newLineKey,
            )
            SettingsDropdownField(
                label = "Submit key", selectedLabel = state.submitKey.preferencesLabel(),
                dropdownState = dropdowns.submitKey,
            )
        }
    }
}

@Composable
public actual fun BoxScope.ApplicationPreferencesDropdownMenus(
    viewModel: ApplicationPreferencesViewModel,
    dropdowns: ApplicationPreferencesDropdowns,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    TuiDropdownMenu(
        dropdownState = dropdowns.newLineKey, options = NewLineKey.entries.toList(),
        selected = state.newLineKey, optionLabel = NewLineKey::preferencesLabel,
        backgroundColor = PopupMenuBackground, onSelect = viewModel::setNewLineKey,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.submitKey, options = SubmitKey.entries.toList(),
        selected = state.submitKey, optionLabel = SubmitKey::preferencesLabel,
        backgroundColor = PopupMenuBackground, onSelect = viewModel::setSubmitKey,
    )
}

@Composable
private fun PreferencesWidthItem(label: String, columns: Int, onChange: (Int) -> Unit) {
    SettingsItem(label = label, supportingText = "$columns columns") {
        SettingsActionButton(
            label = "-", enabled = columns > MinimumSidebarWidthColumns,
            onClick = { onChange(columns - 1) },
        )
        SettingsActionButton(
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
