@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePopup
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryPopup
import io.github.stream29.kodex.openai.*

public actual class SessionSettingsDropdowns actual constructor(
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
public actual fun rememberSessionSettingsDropdowns(): SessionSettingsDropdowns {
    val model = rememberTuiDropdownState()
    val reasoning = rememberTuiDropdownState()
    val tier = rememberTuiDropdownState()
    val questions = rememberTuiDropdownState()
    return remember(model, reasoning, tier, questions) {
        SessionSettingsDropdowns(model, reasoning, tier, questions)
    }
}

@Composable
public actual fun BoxScope.SessionSettingsComponent(viewModel: SessionSettingsViewModel) {
    val dropdowns = rememberSessionSettingsDropdowns()
    SessionSettingsPanel(viewModel, dropdowns)
    SessionSettingsOverlays(viewModel, dropdowns)
}

@Composable
public actual fun SessionSettingsPanel(
    viewModel: SessionSettingsViewModel,
    dropdowns: SessionSettingsDropdowns,
) {
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel, dropdowns) {
        onDispose {
            dropdowns.dismissAll()
            viewModel.hidePage()
        }
    }
    when (val current = state) {
        SessionSettingsState.Unavailable -> Text(
            "No selected session", Modifier.fillMaxWidth().background(SettingsHomeBackground),
            color = SettingsForeground,
        )
        is SessionSettingsState.Available -> Column {
            val snapshot = current.snapshot
            val configuration = snapshot.configuration
            SettingsSection("Identity") {
                SettingsItem("Session name", snapshot.sessionName) {
                    SettingsActionButton("Rename", onClick = { viewModel.requestRename(snapshot.revision) })
                }
                SettingsItem(
                    "Working directory", configuration.workingDirectory.toString(), enabled = snapshot.editable,
                ) {
                    SettingsActionButton(
                        "Browse", enabled = snapshot.editable,
                        onClick = { viewModel.requestWorkingDirectory(snapshot.revision) },
                    )
                }
            }
            SettingsSection("Model behavior") {
                SettingsDropdownField("Model", configuration.model.value, dropdowns.model, enabled = snapshot.editable)
                SettingsDropdownField(
                    "Reasoning", configuration.reasoningEffort.label(), dropdowns.reasoning, enabled = snapshot.editable,
                )
                SettingsDropdownField(
                    "Service tier", configuration.serviceTier.label(), dropdowns.serviceTier, enabled = snapshot.editable,
                )
                SettingsDropdownField(
                    "Questions", configuration.requestUserInputMode.label(), dropdowns.questions,
                    enabled = snapshot.editable, supportingText = "Controls whether the agent may pause to ask for input.",
                )
            }
        }
    }
}

@Composable
public actual fun BoxScope.SessionSettingsOverlays(
    viewModel: SessionSettingsViewModel,
    dropdowns: SessionSettingsDropdowns,
) {
    val state by viewModel.state.collectAsState()
    val rename by viewModel.rename.collectAsState()
    val directory by viewModel.directoryPicker.collectAsState()
    DisposableEffect(viewModel, dropdowns) {
        onDispose {
            dropdowns.dismissAll()
            viewModel.hidePage()
        }
    }
    val available = state as? SessionSettingsState.Available
    if (available != null) {
        val snapshot = available.snapshot
        val configuration = snapshot.configuration
        TuiDropdownMenu(
            dropdowns.model, available.modelOptions, configuration.model, OpenAiModelId::value,
            enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
            onSelect = { viewModel.updateModel(snapshot.revision, it) },
        )
        TuiDropdownMenu(
            dropdowns.reasoning, knownEfforts, configuration.reasoningEffort, ReasoningEffort::label,
            enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
            onSelect = { viewModel.updateReasoningEffort(snapshot.revision, it) },
        )
        TuiDropdownMenu(
            dropdowns.serviceTier, ServiceTier.entries.toList(), configuration.serviceTier, ServiceTier::label,
            enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
            onSelect = { viewModel.updateServiceTier(snapshot.revision, it) },
        )
        TuiDropdownMenu(
            dropdowns.questions, RequestUserInputMode.entries.toList(), configuration.requestUserInputMode,
            RequestUserInputMode::label, enabled = snapshot.editable, backgroundColor = PopupMenuBackground,
            onSelect = { viewModel.updateRequestUserInputMode(snapshot.revision, it) },
        )
    }
    rename?.let { handle ->
        key(handle) {
            SessionRenamePopup(
                handle.viewModel, presentation = SessionRenamePresentation.Labeled,
                onDismissRequest = { viewModel.dismissRename(handle) },
                onSubmitted = { viewModel.dismissRename(handle) },
            )
        }
    }
    directory?.let { handle ->
        key(handle) {
            WorkingDirectoryPopup(
                handle.selection,
                onDismissRequest = { viewModel.dismissWorkingDirectoryPicker(handle) },
                onSelected = {},
            )
        }
    }
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
