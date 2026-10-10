package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.*
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.openai.*

/** Only renderer menu anchors/open state; never a second defaults draft. */
public class NewSessionDefaultsDropdowns(
    public val model: TuiDropdownState,
    public val reasoning: TuiDropdownState,
    public val serviceTier: TuiDropdownState,
    public val questions: TuiDropdownState,
) {
    public fun dismissAll() {
        model.dismiss(); reasoning.dismiss(); serviceTier.dismiss(); questions.dismiss()
    }
}

@Composable
public fun rememberNewSessionDefaultsDropdowns(): NewSessionDefaultsDropdowns {
    val model = rememberTuiDropdownState()
    val reasoning = rememberTuiDropdownState()
    val tier = rememberTuiDropdownState()
    val questions = rememberTuiDropdownState()
    return remember(model, reasoning, tier, questions) {
        NewSessionDefaultsDropdowns(model, reasoning, tier, questions)
    }
}

/** Full four-field component; Title remains an independently owned sibling. */
@Composable
public fun BoxScope.NewSessionDefaultsComponent(
    viewModel: NewSessionSettingsViewModel,
    showOperationFailure: Boolean = true,
) {
    val dropdowns = rememberNewSessionDefaultsDropdowns()
    NewSessionDefaultsPanel(viewModel, dropdowns, showOperationFailure)
    NewSessionDefaultsDropdownMenus(viewModel, dropdowns)
}

/** Scrollable host content. Disable its banner when the Settings host displays shared failure once. */
@Composable
public fun NewSessionDefaultsPanel(
    viewModel: NewSessionSettingsViewModel,
    dropdowns: NewSessionDefaultsDropdowns,
    showOperationFailure: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    val failed by viewModel.operationFailure.collectAsState()
    DisposableEffect(viewModel, dropdowns) { onDispose(dropdowns::dismissAll) }
    Column {
        if (showOperationFailure && failed) {
            SettingsErrorText("A settings operation failed.")
            SettingsActionButton("Dismiss", enabled = state.active, onClick = viewModel::dismissOperationFailure)
        }
        SettingsSection("Model behavior") {
            SettingsDropdownField("Model", state.settings.model.value, dropdowns.model, enabled = state.active)
            SettingsDropdownField(
                "Reasoning", state.settings.reasoningEffort.label(), dropdowns.reasoning, enabled = state.active,
            )
            SettingsDropdownField(
                "Service tier", state.settings.serviceTier.label(), dropdowns.serviceTier, enabled = state.active,
                supportingText = "Ultrafast: higher usage; account access and model support required.",
            )
            SettingsDropdownField(
                "Questions", state.settings.requestUserInputMode.label(), dropdowns.questions,
                enabled = state.active, supportingText = "Controls whether the agent may pause to ask for input.",
            )
        }
    }
}

/** Full canonical menus; callbacks submit the displayed revision and no local draft. */
@Composable
public fun BoxScope.NewSessionDefaultsDropdownMenus(
    viewModel: NewSessionSettingsViewModel,
    dropdowns: NewSessionDefaultsDropdowns,
) {
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel, dropdowns) { onDispose(dropdowns::dismissAll) }
    TuiDropdownMenu(
        dropdowns.model, state.modelOptions, state.settings.model, OpenAiModelId::value,
        enabled = state.active, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateModel(state.revision, it) },
    )
    TuiDropdownMenu(
        dropdowns.reasoning, knownEfforts, state.settings.reasoningEffort, ReasoningEffort::label,
        enabled = state.active, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateReasoningEffort(state.revision, it) },
    )
    TuiDropdownMenu(
        dropdowns.serviceTier, ServiceTier.entries.toList(), state.settings.serviceTier, ServiceTier::label,
        enabled = state.active, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateServiceTier(state.revision, it) },
    )
    TuiDropdownMenu(
        dropdowns.questions, RequestUserInputMode.entries.toList(), state.settings.requestUserInputMode,
        RequestUserInputMode::label, enabled = state.active, backgroundColor = PopupMenuBackground,
        onSelect = { viewModel.updateRequestUserInputMode(state.revision, it) },
    )
}

private fun ReasoningEffort.label(): String = when (this) {
    ReasoningEffort.None -> "none"
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"
    is ReasoningEffort.Custom -> wireName
}
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
private val knownEfforts = listOf(
    ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium,
    ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max,
)
