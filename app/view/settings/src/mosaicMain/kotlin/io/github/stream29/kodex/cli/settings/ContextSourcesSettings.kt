package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiCheckbox
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiInteractionStyle
import io.github.stream29.kodex.cli.components.TuiTheme

@Composable
internal fun ContextSourcesSettingsContent(
    viewModel: GlobalSettingsViewModel,
    onAddSource: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    SettingsSection(
        title = "Built-in sources",
    ) {
        BuiltInSourceItem(
            label = "Agents home",
            path = "~/.agents/",
            source = BuiltInContextSource.AgentsHome,
            enabled = state.contextSources.agentsHomeEnabled,
            onChange = viewModel::setBuiltInContextSourceEnabled,
        )
        BuiltInSourceItem(
            label = "Kodex home",
            path = "~/.kodex/",
            source = BuiltInContextSource.KodexHome,
            enabled = state.contextSources.kodexHomeEnabled,
            onChange = viewModel::setBuiltInContextSourceEnabled,
        )
        BuiltInSourceItem(
            label = "Codex home",
            path = "~/.codex/",
            source = BuiltInContextSource.CodexHome,
            enabled = state.contextSources.codexHomeEnabled,
            onChange = viewModel::setBuiltInContextSourceEnabled,
        )
        BuiltInSourceItem(
            label = "Git root",
            path = "<git-root>/",
            source = BuiltInContextSource.GitRoot,
            enabled = state.contextSources.gitRootEnabled,
            onChange = viewModel::setBuiltInContextSourceEnabled,
        )
        BuiltInSourceItem(
            label = "Working directory",
            path = "<cwd>/",
            source = BuiltInContextSource.WorkingDirectory,
            enabled = state.contextSources.workingDirectoryEnabled,
            onChange = viewModel::setBuiltInContextSourceEnabled,
        )
    }

    SettingsSection(title = "Custom sources") {
        SettingsItem(
            label = "Add a global context source",
            trailing = {
                SettingsActionButton(label = "Add source", onClick = onAddSource)
            },
        )
        state.contextSources.customSources.forEach { source ->
            CustomSourceItem(
                source = source,
                onEnabledChange = { enabled ->
                    viewModel.setCustomContextSourceEnabled(source.path, enabled)
                },
                onRemove = {
                    viewModel.removeCustomContextSource(source.path)
                },
            )
        }
    }
}

@Composable
private fun BuiltInSourceItem(
    label: String,
    path: String,
    source: BuiltInContextSource,
    enabled: Boolean,
    onChange: (BuiltInContextSource, Boolean) -> Unit,
) {
    SettingsCheckboxItem(
        label = label,
        checked = enabled,
        onCheckedChange = { checked -> onChange(source, checked) },
        supportingText = path,
    )
}

@Composable
private fun CustomSourceItem(
    source: io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    SettingsItem(
        label = source.path,
        supportingText = "Global context source",
    ) {
        Row {
            TuiCheckbox(
                label = "Enabled",
                checked = source.enabled,
                onCheckedChange = onEnabledChange,
                color = SettingsForeground,
                idleTextStyle = TuiTheme.typography.body,
                interactionStyle = TuiInteractionStyle.PreserveColors,
            )
            SettingsDangerButton(label = "Remove", onClick = onRemove)
        }
    }
}

@Composable
internal fun BoxScope.ContextSourceAddDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> String?,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, ContextSourceDialogMaximumWidth)
    val input = rememberContextSourceInput()
    var error by remember { mutableStateOf<String?>(null) }

    fun add() {
        val failure = onAdd(input.value.text)
        if (failure == null) {
            onDismiss()
        } else {
            error = failure
        }
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = "Add context source",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text(
                value = "Enter an absolute path, ~, or ~/path.",
                color = SettingsSupportingForeground,
                textStyle = TextStyle.Dim,
            )
            TextInput(
                state = input,
                layout = TextInputLayout.create(input.value, width),
                modifier = Modifier.fillMaxWidth(),
                autoFocus = true,
            )
            error?.let { message -> SettingsErrorText(message) }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(label = "Cancel", onClick = onDismiss)
                SettingsPrimaryButton(label = "Add", onClick = ::add)
            }
        }
    }
}

private fun rememberContextSourceInput(): TextInputState =
    TextInputState(
        TextInputValue(
            text = "",
            cursorOffset = 0,
        ),
    )

private const val ContextSourceDialogMaximumWidth: Int = 96
