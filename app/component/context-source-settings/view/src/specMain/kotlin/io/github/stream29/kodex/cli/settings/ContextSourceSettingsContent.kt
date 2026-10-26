package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer

/** List/commands only; the host owns hidePage and stable child lifetime. */
@Composable
public expect fun ContextSourceSettingsContent(
    viewModel: ContextSourceSettingsViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean = true,
): Unit

/** All callbacks carry the displayed dialog token. The host supplies modal geometry, not another draft. */
@Composable
public expect fun ContextSourceSettingsDialogHost(
    viewModel: ContextSourceSettingsViewModel,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

@Composable
internal expect fun ContextSourceCustomItem(
    path: String, checked: Boolean, onChange: (Boolean) -> Unit, onRemove: () -> Unit,
)

/** Local selection/undo belongs to the renderer; business text remains the exact dialog's VM draft. */
@Composable
internal expect fun ContextSourcePathInput(value: String, onChange: (String) -> Unit)

@Composable
internal expect fun ContextSourceDialogActions(content: @Composable () -> Unit)

@Composable
internal expect fun ContextSourceDialogAction(label: String, primary: Boolean = false, onClick: () -> Unit)
