package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort

/** Renderer-only anchors/open-menu state; no settings, draft, validation or failure authority. */
public class SessionTitleSettingsDropdowns(
    public val model: TuiDropdownState,
    public val reasoning: TuiDropdownState,
)

@Composable
public fun rememberSessionTitleSettingsDropdowns(): SessionTitleSettingsDropdowns =
    SessionTitleSettingsDropdowns(rememberTuiDropdownState(), rememberTuiDropdownState())

/**
 * Complete controls and menus inside a TuiPopupHost. Caller owns navigation/VM lifetime; no VM
 * creation/disposal here. Failure appears here OR in the host by showOperationFailure=false.
 * For a scrollable page use Panel in the viewport and DropdownMenus at the containing host.
 */
@Composable
public fun BoxScope.SessionTitleSettingsComponent(
    viewModel: SessionTitleSettingsViewModel,
    showOperationFailure: Boolean = true,
) {
    val dropdowns = rememberSessionTitleSettingsDropdowns()
    SessionTitleSettingsPanel(viewModel, dropdowns, showOperationFailure)
    SessionTitleSettingsDropdownMenus(viewModel, dropdowns)
}

/** Section/fields only; preserve the same renderer-owned dropdown handles for the overlay. */
@Composable
public fun SessionTitleSettingsPanel(
    viewModel: SessionTitleSettingsViewModel,
    dropdowns: SessionTitleSettingsDropdowns,
    showOperationFailure: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    Column {
        if (showOperationFailure && state.operationFailure) {
            SettingsErrorText("A settings operation failed.")
            SettingsActionButton(label = "Dismiss", onClick = viewModel::dismissFailure)
        }
        SettingsSection(title = "Title generation") {
            SettingsCheckboxItem(
                label = "Automatic session title", checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
            SettingsDropdownField(
                label = "Title model", selectedLabel = state.effectiveModel.value,
                dropdownState = dropdowns.model, enabled = state.enabled,
                supportingText = if (state.enabled) null
                    else "Available when automatic session titles are enabled.",
            )
            SettingsDropdownField(
                label = "Title reasoning", selectedLabel = state.reasoningEffort.titleLabel(),
                dropdownState = dropdowns.reasoning, enabled = state.enabled,
                supportingText = "Reasoning effort used to generate automatic session titles.",
            )
        }
    }
}

/** Both complete baseline menus; disabling rendering does not add VM command rejection. */
@Composable
public fun BoxScope.SessionTitleSettingsDropdownMenus(
    viewModel: SessionTitleSettingsViewModel,
    dropdowns: SessionTitleSettingsDropdowns,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    TuiDropdownMenu(
        dropdownState = dropdowns.model, options = state.modelOptions, selected = state.effectiveModel,
        optionLabel = OpenAiModelId::value, enabled = state.enabled,
        backgroundColor = PopupMenuBackground, onSelect = viewModel::setModel,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.reasoning, options = state.reasoningOptions, selected = state.reasoningEffort,
        optionLabel = ReasoningEffort::titleLabel, enabled = state.enabled,
        backgroundColor = PopupMenuBackground, onSelect = viewModel::setReasoningEffort,
    )
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
