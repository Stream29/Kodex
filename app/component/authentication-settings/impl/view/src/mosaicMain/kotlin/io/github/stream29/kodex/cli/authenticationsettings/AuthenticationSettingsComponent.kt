package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState

/**
 * Full component inside a TuiPopupHost. Renderer owns only source menu/focus; host calls hidePage
 * on navigation and close at Settings end. It does not close the stable child on panel unmount.
 * For host scroll layout, compose Panel within content and Overlays at popup-host level using
 * the same rememberTuiDropdownState.
 */
@Composable
public fun BoxScope.AuthenticationSettingsComponent(
    viewModel: AuthenticationSettingsViewModel,
    showOperationFailure: Boolean = true,
) {
    val dropdown = rememberTuiDropdownState()
    AuthenticationSettingsPanel(viewModel, dropdown, showOperationFailure)
    AuthenticationSettingsOverlays(viewModel, dropdown)
}

/** Source trigger, safe summary and operations; false suppresses the host-owned shared banner. */
@Composable
public fun AuthenticationSettingsPanel(
    viewModel: AuthenticationSettingsViewModel,
    sourceDropdown: TuiDropdownState,
    showOperationFailure: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    AuthenticationSettingsContent(
        state, sourceDropdown, viewModel::requestLogin, viewModel::requestLogout,
        viewModel::dismissFailure, showOperationFailure,
    )
}

/** Full safe renderer: disabled UI does not add command-side guards or optimistic auth changes. */
@Composable
public fun AuthenticationSettingsContent(
    state: AuthenticationSettingsState,
    sourceDropdown: TuiDropdownState,
    onOpenLogin: () -> Unit,
    onRequestLogout: () -> Unit,
    onDismissFailure: () -> Unit,
    showOperationFailure: Boolean = true,
) {
    if (state.closed) return
    val enabled = state.operation != SettingsAuthenticationOperationState.SigningOut
    Column(modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surface)) {
        Row {
            Text("Authentication: ")
            TuiDropdownTrigger(
                dropdownState = sourceDropdown, label = state.selectedSource.name,
                enabled = enabled, autoFocus = true,
            )
        }
        Text("OpenAI account", textStyle = TuiTheme.typography.title)
        Text(state.authentication.summary(), textStyle = TuiTheme.typography.supporting)
        Text("${state.selectedSource.name} credentials")
        Text("Maintained by the backend for the selected source.", textStyle = TuiTheme.typography.supporting)
        Text("Browser sign-in")
        TuiButton(
            label = if (state.authentication is SettingsAuthenticationState.Authenticated) "Sign in again" else "Sign in",
            enabled = enabled, onClick = onOpenLogin,
        )
        if (state.authentication is SettingsAuthenticationState.Authenticated) {
            Text("Remove selected credentials")
            TuiButton(
                label = "Log out", enabled = enabled,
                color = TuiTheme.colorScheme.error, onClick = onRequestLogout,
            )
        }
        when (state.operation) {
            SettingsAuthenticationOperationState.Idle -> Unit
            SettingsAuthenticationOperationState.SigningOut -> Text("Signing out…")
            is SettingsAuthenticationOperationState.Failed -> {
                Text(
                    "Could not log out. Check the selected source before trying again.",
                    color = TuiTheme.colorScheme.error,
                )
                TuiButton(label = "Dismiss", onClick = onDismissFailure)
            }
        }
        // The specific local operation message is sufficient acknowledgement on this surface.
        if (showOperationFailure && state.operationFailure &&
            state.operation !is SettingsAuthenticationOperationState.Failed) {
            Text("A settings operation failed.", color = TuiTheme.colorScheme.error)
            TuiButton(label = "Dismiss", onClick = onDismissFailure)
        }
    }
}

/** Source menu plus exact-identity confirmation; place after persistent content in TuiPopupHost. */
@Composable
public fun BoxScope.AuthenticationSettingsOverlays(
    viewModel: AuthenticationSettingsViewModel,
    sourceDropdown: TuiDropdownState,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    TuiDropdownMenu(
        dropdownState = sourceDropdown,
        options = KodexAuthSource.entries,
        selected = state.selectedSource,
        optionLabel = { it.name },
        enabled = state.operation != SettingsAuthenticationOperationState.SigningOut,
        onSelect = { viewModel.updateSource(it); sourceDropdown.dismiss() },
        backgroundColor = TuiTheme.colorScheme.surfaceContainer,
    )
    state.confirmation?.let { expected ->
        AuthenticationLogoutDialog(
            source = state.selectedSource,
            onDismiss = { viewModel.cancelLogout(expected) },
            onConfirm = { viewModel.confirmLogout(expected) },
        )
    }
}

/** Cancel initially focused; wording describes only the live selected source's local file. */
@Composable
public fun BoxScope.AuthenticationLogoutDialog(
    source: KodexAuthSource,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(TuiTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Text("Log out of OpenAI", textStyle = TuiTheme.typography.headline)
            Text(when (source) {
                KodexAuthSource.Kodex -> "Remove Kodex private credentials?"
                KodexAuthSource.Codex -> "Remove credentials from ~/.codex/auth.json?"
            })
            Text(when (source) {
                KodexAuthSource.Kodex -> "Codex CLI credentials are not affected."
                KodexAuthSource.Codex -> "Kodex private credentials are not affected."
            })
            Text("This removes local credentials only; it does not revoke access remotely.",
                textStyle = TuiTheme.typography.supporting)
            TuiDialogActionRow(modifier = Modifier.fillMaxWidth()) {
                TuiButton(label = "Cancel", autoFocus = true, onClick = onDismiss)
                TuiButton(label = "Log out", color = TuiTheme.colorScheme.error, onClick = onConfirm)
            }
        }
    }
}

private fun SettingsAuthenticationState.summary(): String = when (this) {
    is SettingsAuthenticationState.Authenticated -> {
        val identity = email?.let { "Signed in as $it" }
            ?: accountId?.let { "Signed in as account $it" } ?: "Signed in"
        planType?.let { "$identity Plan: ${it.rawValue}" } ?: identity
    }
    is SettingsAuthenticationState.Unavailable -> "Authentication unavailable: ${reason.description()}"
}

private fun OpenAiAuthState.Unavailable.description(): String = when (this) {
    OpenAiAuthState.Unavailable.NotLoaded -> "Authentication credentials have not been loaded yet."
    OpenAiAuthState.Unavailable.CredentialsNotFound -> "No credentials were found in the selected authentication source."
    OpenAiAuthState.Unavailable.UnsupportedAuthMode -> "The selected credentials use an unsupported authentication mode."
    OpenAiAuthState.Unavailable.InvalidCredentials -> "The selected credentials are malformed or incomplete."
    OpenAiAuthState.Unavailable.CredentialSourceUnavailable -> "The selected credential source could not be read."
    OpenAiAuthState.Unavailable.UnexpectedFailure -> "Authentication failed because of an unexpected internal error."
}
