package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsDialog
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.app.ui.*

@Composable
public actual fun ContextSourceSettingsContent(
    viewModel: ContextSourceSettingsViewModel, renderer: SettingsRenderer,
) {
    val state = viewModel.state.collectAsState().value
    if (state.closed) return
    key(viewModel) { UiColumn {
        renderer.Section("Built-in sources") {
            renderer.Toggle("Agents home", state.sources.agentsHomeEnabled,
                { viewModel.setBuiltInEnabled(BuiltInContextSource.AgentsHome, it) }, enabled = true, supportingText = "~/.agents/")
            renderer.Toggle("Kodex home", state.sources.kodexHomeEnabled,
                { viewModel.setBuiltInEnabled(BuiltInContextSource.KodexHome, it) }, enabled = true, supportingText = "~/.kodex/")
            renderer.Toggle("Codex home", state.sources.codexHomeEnabled,
                { viewModel.setBuiltInEnabled(BuiltInContextSource.CodexHome, it) }, enabled = true, supportingText = "~/.codex/")
            renderer.Toggle("Git root", state.sources.gitRootEnabled,
                { viewModel.setBuiltInEnabled(BuiltInContextSource.GitRoot, it) }, enabled = true, supportingText = "<git-root>/")
            renderer.Toggle("Working directory", state.sources.workingDirectoryEnabled,
                { viewModel.setBuiltInEnabled(BuiltInContextSource.WorkingDirectory, it) }, enabled = true, supportingText = "<cwd>/")
        }
        renderer.Section("Custom sources") {
            renderer.Item("Add a global context source", supportingText = null, enabled = true) {
                renderer.Action("Add source", onClick = viewModel::add, enabled = true, autoFocus = false, role = UiActionRole.Normal)
            }
            if (state.sources.customSources.isEmpty()) UiText("None configured", UiTextRole.Supporting)
            state.sources.customSources.forEach { source ->
                key(source.path) {
                    renderer.Item(source.path, supportingText = "Global context source", enabled = true) {
                        renderer.InlineToggle(
                            label = "Enabled",
                            contentDescription = "${source.path}: Enabled",
                            checked = source.enabled,
                            onChange = { viewModel.setCustomEnabled(source.path, it) },
                            enabled = true,
                        )
                        renderer.Action(
                            label = "Remove",
                            onClick = { viewModel.removeCustom(source.path) },
                            enabled = true,
                            autoFocus = false,
                            role = UiActionRole.Destructive,
                        )
                    }
                }
            }
        }
    } }
}

@Composable
public actual fun ContextSourceSettingsDialogHost(
    viewModel: ContextSourceSettingsViewModel,
    renderer: SettingsRenderer,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    val state = viewModel.state.collectAsState().value
    if (state.closed) return
    when (val dialog = state.dialog) {
        ContextSourceSettingsDialog.Hidden -> Unit
        is ContextSourceSettingsDialog.Adding -> key(viewModel, dialog.token) {
            val dismiss = { viewModel.dismiss(dialog.token) }
            frame("Add context source", dismiss) {
                UiText("Enter an absolute path, ~, or ~/path.", UiTextRole.Supporting)
                renderer.TextField(dialog.token, "Context source path", dialog.draft, autoFocus = true) {
                    viewModel.updateDraft(dialog.token, it)
                }
                dialog.error?.let { UiText(it, UiTextRole.Error) }
                UiActions {
                    UiAction("Cancel", onClick = dismiss)
                    UiAction("Add", onClick = { viewModel.save(dialog.token) }, role = UiActionRole.Primary)
                }
            }
        }
    }
}
