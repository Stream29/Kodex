package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.settings.KodexAuthSource

/**
 * Full component inside a TuiPopupHost. Renderer owns only source menu/focus; host calls hidePage
 * on navigation and close at Settings end. It does not close the stable child on panel unmount.
 * For host scroll layout, compose Panel within content and Overlays at popup-host level using
 * the same rememberTuiDropdownState.
 */
@Composable
public expect fun BoxScope.AuthenticationSettingsComponent(
    viewModel: AuthenticationSettingsViewModel,
    showOperationFailure: Boolean = true,
): Unit

/** Source trigger, safe summary and operations; false suppresses the host-owned shared banner. */
@Composable
public expect fun AuthenticationSettingsPanel(
    viewModel: AuthenticationSettingsViewModel,
    sourceDropdown: TuiDropdownState,
    showOperationFailure: Boolean = true,
): Unit

/** Full safe renderer: disabled UI does not add command-side guards or optimistic auth changes. */
@Composable
public expect fun AuthenticationSettingsContent(
    state: AuthenticationSettingsState,
    sourceDropdown: TuiDropdownState,
    onOpenLogin: () -> Unit,
    onRequestLogout: () -> Unit,
    onDismissFailure: () -> Unit,
    showOperationFailure: Boolean = true,
): Unit

/** Source menu plus exact-identity confirmation; place after persistent content in TuiPopupHost. */
@Composable
public expect fun BoxScope.AuthenticationSettingsOverlays(
    viewModel: AuthenticationSettingsViewModel,
    sourceDropdown: TuiDropdownState,
): Unit

/** Cancel initially focused; wording describes only the live selected source's local file. */
@Composable
public expect fun BoxScope.AuthenticationLogoutDialog(
    source: KodexAuthSource,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
): Unit
