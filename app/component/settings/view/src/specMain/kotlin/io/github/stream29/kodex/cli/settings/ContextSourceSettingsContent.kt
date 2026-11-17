package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer

/** List/commands only; the host owns hidePage and stable child lifetime. */
@Composable
public expect fun ContextSourceSettingsContent(
    viewModel: ContextSourceSettingsViewModel,
    renderer: SettingsRenderer,
): Unit

/** All callbacks carry the displayed dialog token. The host supplies modal geometry, not another draft. */
@Composable
public expect fun ContextSourceSettingsDialogHost(
    viewModel: ContextSourceSettingsViewModel,
    renderer: SettingsRenderer,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit
