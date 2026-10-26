package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SettingsHostCapabilities
import kotlinx.coroutines.flow.StateFlow

/**
 * Graphical root over the shared application composition. Mount inside UiTheme and a finite
 * Compose UI surface. Borrows all owners; unmount does not shut down the Application/backend.
 * Authentication, RPC connection ownership and URL launching remain the browser host's concern.
 * Existing width preferences are logical columns measured with this renderer's current '0' advance,
 * not terminal cells or raw pixels. Dragging converts back before updating the same width owner.
 */
@Composable
public expect fun ComposeApplicationScreen(
    viewModel: ApplicationViewModel,
    newLineKey: StateFlow<NewLineKey>,
    sidebarSettings: SidebarSettingsViewModel,
    onOperationFailure: (Throwable) -> Unit,
    openExternalUrl: suspend (String) -> Boolean,
    capabilities: SettingsHostCapabilities = SettingsHostCapabilities(),
): Unit
