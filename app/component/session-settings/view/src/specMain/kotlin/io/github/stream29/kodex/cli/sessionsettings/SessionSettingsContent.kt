package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.settings.contract.SessionSettingsRename
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionWorkingDirectoryPicker
import io.github.stream29.kodex.app.ui.SettingsRenderer

public enum class SessionChoiceField { Model, Reasoning, ServiceTier, Questions }

/** Unmount hides unaccepted interactions, never closes the borrowed Settings child or its source. */
@Composable
public expect fun SessionSettingsContent(
    viewModel: SessionSettingsViewModel,
    renderer: SettingsRenderer,
    directoryBrowsingAvailable: Boolean = true,
): Unit

/** Exact child handles; renderer slots supply modal geometry and platform-specific directory capability. */
@Composable
public expect fun SessionSettingsChildren(
    viewModel: SessionSettingsViewModel,
    rename: @Composable (handle: SessionSettingsRename, onDismiss: () -> Unit) -> Unit,
    directory: @Composable (handle: SessionWorkingDirectoryPicker, onDismiss: () -> Unit) -> Unit,
): Unit

@Composable
internal expect fun UnavailableSessionSettingsContent()
