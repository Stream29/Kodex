package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.cli.settings.KodexAuthSource

/** Panel only; the host places confirmation overlays outside its scrolling content. */
@Composable
public expect fun AuthenticationSettingsPage(
    viewModel: AuthenticationSettingsViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean = true,
    loginAvailable: Boolean = true,
): Unit

/** Source menu geometry belongs to the host, not the authentication child or shared content. */
@Composable
public expect fun AuthenticationSettingsBody(
    state: AuthenticationSettingsState,
    onOpenLogin: () -> Unit,
    onRequestLogout: () -> Unit,
    onDismissFailure: () -> Unit,
    showOperationFailure: Boolean = true,
    loginAvailable: Boolean = true,
    sourceSelector: @Composable (enabled: Boolean) -> Unit,
): Unit

/** Captures the exact confirmation object; live source wording does not retarget an admitted remove. */
@Composable
public expect fun AuthenticationLogoutHost(
    viewModel: AuthenticationSettingsViewModel,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

/** Confirmation body; its host renders the heading and modal geometry. */
@Composable
public expect fun AuthenticationLogoutContent(
    source: KodexAuthSource,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
): Unit

@Composable
internal expect fun AuthenticationSettingsFrame(content: @Composable () -> Unit)
