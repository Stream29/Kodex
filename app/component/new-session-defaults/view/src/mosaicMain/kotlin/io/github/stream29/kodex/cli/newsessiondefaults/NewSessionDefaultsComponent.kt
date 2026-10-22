@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.*
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.openai.*

public actual class NewSessionDefaultsDropdowns actual constructor(
    public actual val model: TuiDropdownState,
    public actual val reasoning: TuiDropdownState,
    public actual val serviceTier: TuiDropdownState,
    public actual val questions: TuiDropdownState,
) {
    public actual fun dismissAll() {
        model.dismiss(); reasoning.dismiss(); serviceTier.dismiss(); questions.dismiss()
    }
}

@Composable
public actual fun rememberNewSessionDefaultsDropdowns(): NewSessionDefaultsDropdowns {
    val model = rememberTuiDropdownState()
    val reasoning = rememberTuiDropdownState()
    val tier = rememberTuiDropdownState()
    val questions = rememberTuiDropdownState()
    return remember(model, reasoning, tier, questions) {
        NewSessionDefaultsDropdowns(model, reasoning, tier, questions)
    }
}

@Composable
public actual fun BoxScope.NewSessionDefaultsComponent(
    viewModel: NewSessionSettingsViewModel,
    showOperationFailure: Boolean,
) {
    val dropdowns = rememberNewSessionDefaultsDropdowns()
    NewSessionDefaultsPanel(viewModel, dropdowns, showOperationFailure)
    NewSessionDefaultsDropdownMenus(viewModel, dropdowns)
}

@Composable
public actual fun NewSessionDefaultsPanel(
    viewModel: NewSessionSettingsViewModel,
    dropdowns: NewSessionDefaultsDropdowns,
    showOperationFailure: Boolean,
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
            )
            SettingsDropdownField(
                "Questions", state.settings.requestUserInputMode.label(), dropdowns.questions,
                enabled = state.active, supportingText = "Controls whether the agent may pause to ask for input.",
            )
        }
    }
}

@Composable
public actual fun BoxScope.NewSessionDefaultsDropdownMenus(
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
