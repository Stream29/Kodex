package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState

/** Anchors/menu-open state only; not another preferences draft. */
public class ApplicationPreferencesDropdowns(
    public val newLineKey: TuiDropdownState,
    public val submitKey: TuiDropdownState,
)

@Composable
public fun rememberApplicationPreferencesDropdowns(): ApplicationPreferencesDropdowns =
    ApplicationPreferencesDropdowns(rememberTuiDropdownState(), rememberTuiDropdownState())

/**
 * Complete General controls and both menus inside a TuiPopupHost, no VM creation/lifecycle ownership.
 * For scrollable hosts use Panel and DropdownMenus with the same renderer-only handles. Suppress
 * the shared failure acknowledgement only when the host already renders that source once.
 */
@Composable
public fun BoxScope.ApplicationPreferencesComponent(
    viewModel: ApplicationPreferencesViewModel,
    showOperationFailure: Boolean = true,
) {
    val dropdowns = rememberApplicationPreferencesDropdowns()
    ApplicationPreferencesPanel(viewModel, dropdowns, showOperationFailure)
    ApplicationPreferencesDropdownMenus(viewModel, dropdowns)
}

/** Sidebars then Input; +/- captures displayed columns, never silently refreshes before applying. */
@Composable
public fun ApplicationPreferencesPanel(
    viewModel: ApplicationPreferencesViewModel,
    dropdowns: ApplicationPreferencesDropdowns,
    showOperationFailure: Boolean = true,
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

/** Overlay for the two canonical paired-key choices. */
@Composable
public fun BoxScope.ApplicationPreferencesDropdownMenus(
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
