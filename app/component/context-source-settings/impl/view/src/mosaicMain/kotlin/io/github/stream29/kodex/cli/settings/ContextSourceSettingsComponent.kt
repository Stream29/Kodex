package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsDialog
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.cli.components.*

/**
 * Complete independent list/dialog surface inside a TuiPopupHost. The host owns navigation/lifetime,
 * calls hidePage on departure and close on disposal; this renderer never creates or closes the VM.
 * Suppress the shared failure banner only when the host already renders that same source once.
 */
@Composable
public fun BoxScope.ContextSourceSettingsComponent(
    viewModel: ContextSourceSettingsViewModel,
    showOperationFailure: Boolean = true,
) {
    ContextSourceSettingsPanel(viewModel, showOperationFailure)
    ContextSourceSettingsDialogs(viewModel)
}

/** Scrollable list surface; pair with ContextSourceSettingsDialogs at the containing popup host. */
@Composable
public fun ContextSourceSettingsPanel(
    viewModel: ContextSourceSettingsViewModel,
    showOperationFailure: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    Column {
        if (showOperationFailure && state.operationFailure) {
            SettingsErrorText("A settings operation failed.")
            SettingsActionButton(label = "Dismiss", onClick = viewModel::dismissFailure)
        }
        SettingsSection(title = "Built-in sources") {
            BuiltInItem("Agents home", "~/.agents/", BuiltInContextSource.AgentsHome,
                state.sources.agentsHomeEnabled, viewModel)
            BuiltInItem("Kodex home", "~/.kodex/", BuiltInContextSource.KodexHome,
                state.sources.kodexHomeEnabled, viewModel)
            BuiltInItem("Codex home", "~/.codex/", BuiltInContextSource.CodexHome,
                state.sources.codexHomeEnabled, viewModel)
            BuiltInItem("Git root", "<git-root>/", BuiltInContextSource.GitRoot,
                state.sources.gitRootEnabled, viewModel)
            BuiltInItem("Working directory", "<cwd>/", BuiltInContextSource.WorkingDirectory,
                state.sources.workingDirectoryEnabled, viewModel)
        }
        SettingsSection(title = "Custom sources") {
            SettingsItem(label = "Add a global context source") {
                SettingsActionButton(label = "Add source", onClick = viewModel::add)
            }
            if (state.sources.customSources.isEmpty()) {
                Text("None configured", color = SettingsSupportingForeground)
            }
            state.sources.customSources.forEach { source ->
                SettingsItem(label = source.path, supportingText = "Global context source") {
                    Row {
                        TuiCheckbox(
                            label = "Enabled", checked = source.enabled,
                            onCheckedChange = { viewModel.setCustomEnabled(source.path, it) },
                            color = SettingsForeground, idleTextStyle = TuiTheme.typography.body,
                            interactionStyle = TuiInteractionStyle.PreserveColors,
                        )
                        SettingsDangerButton(label = "Remove", onClick = { viewModel.removeCustom(source.path) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInItem(
    label: String, path: String, source: BuiltInContextSource,
    enabled: Boolean, viewModel: ContextSourceSettingsViewModel,
) {
    SettingsCheckboxItem(
        label = label, checked = enabled, supportingText = path,
        onCheckedChange = { viewModel.setBuiltInEnabled(source, it) },
    )
}

/** Exact-token dialog overlay. VM draft/error is authoritative; local input stores cursor/undo only. */
@Composable
public fun BoxScope.ContextSourceSettingsDialogs(viewModel: ContextSourceSettingsViewModel) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    when (val dialog = state.dialog) {
        ContextSourceSettingsDialog.Hidden -> Unit
        is ContextSourceSettingsDialog.Adding -> {
            val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 96)
            val input = remember(dialog.token) { TextInputState(TextInputValue(dialog.draft)) }
            LaunchedEffect(dialog.token, dialog.draft) {
                if (input.value.text != dialog.draft) {
                    input.reset(TextInputValue(dialog.draft, input.value.cursorOffset.coerceAtMost(dialog.draft.length)))
                }
            }
            TuiDialog(
                onDismissRequest = { viewModel.dismiss(dialog.token) },
                modifier = Modifier.width(width).background(SettingsDialogBackground),
            ) {
                Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
                    Text(
                        "Add context source",
                        modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                        color = SettingsForeground, textStyle = TuiTheme.typography.headline,
                    )
                    Text(
                        "Enter an absolute path, ~, or ~/path.",
                        color = SettingsSupportingForeground, textStyle = TextStyle.Dim,
                    )
                    TextInput(
                        state = input, layout = TextInputLayout.create(input.value, width),
                        modifier = Modifier.fillMaxWidth(), autoFocus = true,
                        onValueChanged = { viewModel.updateDraft(dialog.token, it.text) },
                    )
                    dialog.error?.let { SettingsErrorText(it) }
                    TuiDialogActionRow(Modifier.fillMaxWidth().background(SettingsActionBackground)) {
                        SettingsActionButton(label = "Cancel", onClick = { viewModel.dismiss(dialog.token) })
                        SettingsPrimaryButton(label = "Add", onClick = { viewModel.save(dialog.token) })
                    }
                }
            }
        }
    }
}
