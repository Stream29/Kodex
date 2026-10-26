package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.OpenAiAuthState

@Composable
public actual fun AuthenticationSettingsPage(
    viewModel: AuthenticationSettingsViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean,
    loginAvailable: Boolean,
) {
    val state by viewModel.state.collectAsState()
    key(viewModel) {
        AuthenticationSettingsBody(
            state, viewModel::requestLogin, viewModel::requestLogout, viewModel::dismissFailure,
            showOperationFailure, loginAvailable,
        ) { enabled ->
            renderer.Choice(
                fieldKey = "AuthenticationSource", label = "Authentication",
                options = KodexAuthSource.entries.map { UiChoiceOption(it.name, it.name) },
                selectedKey = state.selectedSource.name,
                onSelect = { key -> KodexAuthSource.entries.firstOrNull { it.name == key }?.let(viewModel::updateSource) },
                enabled = enabled,
            )
        }
    }
}

@Composable
public actual fun AuthenticationSettingsBody(
    state: AuthenticationSettingsState,
    onOpenLogin: () -> Unit,
    onRequestLogout: () -> Unit,
    onDismissFailure: () -> Unit,
    showOperationFailure: Boolean,
    loginAvailable: Boolean,
    sourceSelector: @Composable (enabled: Boolean) -> Unit,
) {
    if (state.closed) return
    val enabled = state.operation != SettingsAuthenticationOperationState.SigningOut
    AuthenticationSettingsFrame {
        sourceSelector(enabled)
        UiText("OpenAI account", UiTextRole.Title)
        UiText(state.authentication.summary(), UiTextRole.Supporting)
        UiText("${state.selectedSource.name} credentials")
        UiText("Maintained by the backend for the selected source.", UiTextRole.Supporting)
        UiText("Browser sign-in")
        UiAction(
            label = if (state.authentication is SettingsAuthenticationState.Authenticated) "Sign in again" else "Sign in",
            enabled = enabled && loginAvailable, onClick = onOpenLogin,
        )
        if (!loginAvailable) UiText("Provider sign-in is unavailable in this host. Sign in using the Native client.", UiTextRole.Supporting)
        if (state.authentication is SettingsAuthenticationState.Authenticated) {
            UiText("Remove selected credentials")
            UiAction("Log out", enabled = enabled, role = UiActionRole.Destructive, onClick = onRequestLogout)
        }
        when (state.operation) {
            SettingsAuthenticationOperationState.Idle -> Unit
            SettingsAuthenticationOperationState.SigningOut -> UiText("Signing out…")
            is SettingsAuthenticationOperationState.Failed -> {
                UiText("Could not log out. Check the selected source before trying again.", UiTextRole.Error)
                UiAction("Dismiss", onClick = onDismissFailure)
            }
        }
        if (showOperationFailure && state.operationFailure &&
            state.operation !is SettingsAuthenticationOperationState.Failed) {
            UiText("A settings operation failed.", UiTextRole.Error)
            UiAction("Dismiss", onClick = onDismissFailure)
        }
    }
}

@Composable
public actual fun AuthenticationLogoutHost(
    viewModel: AuthenticationSettingsViewModel,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    state.confirmation?.let { expected ->
        key(viewModel, expected) {
            val dismiss = { viewModel.cancelLogout(expected) }
            frame("Log out of OpenAI", dismiss) {
                AuthenticationLogoutContent(state.selectedSource, dismiss) { viewModel.confirmLogout(expected) }
            }
        }
    }
}

@Composable
public actual fun AuthenticationLogoutContent(
    source: KodexAuthSource, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    UiColumn {
        UiText(when (source) {
            KodexAuthSource.Kodex -> "Remove Kodex private credentials?"
            KodexAuthSource.Codex -> "Remove credentials from ~/.codex/auth.json?"
        })
        UiText(when (source) {
            KodexAuthSource.Kodex -> "Codex CLI credentials are not affected."
            KodexAuthSource.Codex -> "Kodex private credentials are not affected."
        })
        UiText("This removes local credentials only; it does not revoke access remotely.", UiTextRole.Supporting)
        UiActions {
            UiAction("Cancel", autoFocus = true, onClick = onDismiss)
            UiAction("Log out", role = UiActionRole.Destructive, onClick = onConfirm)
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
