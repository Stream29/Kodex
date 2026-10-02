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

/** Renderer-only menu anchors/open state; contains no configuration or target/draft authority. */
public class SessionSettingsDropdowns(
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
public fun rememberSessionSettingsDropdowns(): SessionSettingsDropdowns {
    val model = rememberTuiDropdownState()
    val reasoning = rememberTuiDropdownState()
    val tier = rememberTuiDropdownState()
    val questions = rememberTuiDropdownState()
    return remember(model, reasoning, tier, questions) {
        SessionSettingsDropdowns(model, reasoning, tier, questions)
    }
}

/** Complete independently renderable component. Removal hides the page, never closes the stable VM. */
@Composable
public fun BoxScope.SessionSettingsComponent(viewModel: SessionSettingsViewModel) {
    val dropdowns = rememberSessionSettingsDropdowns()
    SessionSettingsPanel(viewModel, dropdowns)
    SessionSettingsOverlays(viewModel, dropdowns)
}

/** Scrollable host content; pass the same renderer menu object to [SessionSettingsOverlays]. */
@Composable
public fun SessionSettingsPanel(
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

/**
 * Full four menus and borrowed exact child renderers. No effects collector or renderer-local name
 * draft/target. Child renderers retain their existing disposal and Labeled presentation contracts.
 */
@Composable
public fun BoxScope.SessionSettingsOverlays(
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
}
private fun RequestUserInputMode.label(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}
private val knownEfforts = listOf(
    ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium,
    ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max,
)
