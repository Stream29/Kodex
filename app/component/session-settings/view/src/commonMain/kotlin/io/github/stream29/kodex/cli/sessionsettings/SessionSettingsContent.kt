package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.openai.*

@Composable
public actual fun SessionSettingsContent(
    viewModel: SessionSettingsViewModel, renderer: SettingsRenderer, directoryBrowsingAvailable: Boolean,
) {
    val state = viewModel.state.collectAsState().value
    DisposableEffect(viewModel) { onDispose(viewModel::hidePage) }
    key(viewModel) {
        when (val current = state) {
            SessionSettingsState.Unavailable -> UnavailableSessionSettingsContent()
            is SessionSettingsState.Available -> UiColumn {
                val snapshot = current.snapshot
                val configuration = snapshot.configuration
                renderer.Section("Identity") {
                    renderer.Item("Session name", snapshot.sessionName, enabled = true) {
                        renderer.Action("Rename", onClick = { viewModel.requestRename(snapshot.revision) }, enabled = true, autoFocus = false)
                    }
                    renderer.Item("Working directory", configuration.workingDirectory.toString(), enabled = snapshot.editable) {
                        renderer.Action(
                            "Browse", enabled = snapshot.editable && directoryBrowsingAvailable,
                            onClick = { viewModel.requestWorkingDirectory(snapshot.revision) }, autoFocus = false,
                        )
                    }
                }
                renderer.Section("Model behavior") {
                    renderer.Choice(
                        SessionChoiceField.Model.name, "Model",
                        current.modelOptions.map { UiChoiceOption(it.value, it.value) }, configuration.model.value,
                        onSelect = { selected -> current.modelOptions.firstOrNull { it.value == selected }?.let {
                            viewModel.updateModel(snapshot.revision, it)
                        } }, enabled = snapshot.editable, supportingText = null,
                    )
                    renderer.Choice(
                        SessionChoiceField.Reasoning.name, "Reasoning",
                        knownEfforts.map { UiChoiceOption(it.wireName, it.wireName) }, configuration.reasoningEffort.wireName,
                        onSelect = { selected -> knownEfforts.firstOrNull { it.wireName == selected }?.let {
                            viewModel.updateReasoningEffort(snapshot.revision, it)
                        } }, enabled = snapshot.editable, supportingText = null,
                    )
                    renderer.Choice(
                        SessionChoiceField.ServiceTier.name, "Service tier",
                        ServiceTier.entries.map { UiChoiceOption(it.name, it.label()) }, configuration.serviceTier.name,
                        onSelect = { selected -> ServiceTier.entries.firstOrNull { it.name == selected }?.let {
                            viewModel.updateServiceTier(snapshot.revision, it)
                        } }, enabled = snapshot.editable, supportingText = null,
                    )
                    renderer.Choice(
                        SessionChoiceField.Questions.name, "Questions",
                        RequestUserInputMode.entries.map { UiChoiceOption(it.name, it.label()) }, configuration.requestUserInputMode.name,
                        onSelect = { selected -> RequestUserInputMode.entries.firstOrNull { it.name == selected }?.let {
                            viewModel.updateRequestUserInputMode(snapshot.revision, it)
                        } }, enabled = snapshot.editable,
                        supportingText = "Controls whether the agent may pause to ask for input.",
                    )
                }
            }
        }
    }
}

@Composable
public actual fun SessionSettingsChildren(
    viewModel: SessionSettingsViewModel,
    rename: @Composable (handle: SessionSettingsRename, onDismiss: () -> Unit) -> Unit,
    directory: @Composable (handle: SessionWorkingDirectoryPicker, onDismiss: () -> Unit) -> Unit,
) {
    val renameHandle by viewModel.rename.collectAsState()
    val directoryHandle by viewModel.directoryPicker.collectAsState()
    DisposableEffect(viewModel) { onDispose(viewModel::hidePage) }
    renameHandle?.let { handle ->
        key(viewModel, handle) { rename(handle) { viewModel.dismissRename(handle) } }
    }
    directoryHandle?.let { handle ->
        key(viewModel, handle) { directory(handle) { viewModel.dismissWorkingDirectoryPicker(handle) } }
    }
}

private val knownEfforts = listOf(
    ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium,
    ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max,
)

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
