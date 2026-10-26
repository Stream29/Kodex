package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsState
import io.github.stream29.kodex.app.authenticationsettings.AuthenticationSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.settings.KodexAuthSource

@Composable
public actual fun BoxScope.AuthenticationSettingsComponent(
    viewModel: AuthenticationSettingsViewModel, showOperationFailure: Boolean,
) {
    val dropdown = rememberTuiDropdownState()
    AuthenticationSettingsPanel(viewModel, dropdown, showOperationFailure)
    AuthenticationSettingsOverlays(viewModel, dropdown)
}

@Composable
public actual fun AuthenticationSettingsPanel(
    viewModel: AuthenticationSettingsViewModel, sourceDropdown: TuiDropdownState, showOperationFailure: Boolean,
) {
    val state by viewModel.state.collectAsState()
    AuthenticationSettingsContent(
        state, sourceDropdown, viewModel::requestLogin, viewModel::requestLogout,
        viewModel::dismissFailure, showOperationFailure,
    )
}

@Composable
public actual fun AuthenticationSettingsContent(
    state: AuthenticationSettingsState, sourceDropdown: TuiDropdownState,
    onOpenLogin: () -> Unit, onRequestLogout: () -> Unit, onDismissFailure: () -> Unit,
    showOperationFailure: Boolean,
) {
    AuthenticationSettingsBody(state, onOpenLogin, onRequestLogout, onDismissFailure, showOperationFailure) { enabled ->
        Row {
            Text("Authentication: ")
            TuiDropdownTrigger(sourceDropdown, state.selectedSource.name, enabled = enabled, autoFocus = true)
        }
    }
}

@Composable
public actual fun BoxScope.AuthenticationSettingsOverlays(
    viewModel: AuthenticationSettingsViewModel, sourceDropdown: TuiDropdownState,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    TuiDropdownMenu(
        dropdownState = sourceDropdown, options = KodexAuthSource.entries, selected = state.selectedSource,
        optionLabel = { it.name }, enabled = state.operation != SettingsAuthenticationOperationState.SigningOut,
        onSelect = { viewModel.updateSource(it); sourceDropdown.dismiss() },
        backgroundColor = TuiTheme.colorScheme.surfaceContainer,
    )
    AuthenticationLogoutHost(viewModel) { title, dismiss, content ->
        AuthenticationLogoutFrame(title, dismiss, content)
    }
}

@Composable
public actual fun BoxScope.AuthenticationLogoutDialog(
    source: KodexAuthSource, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AuthenticationLogoutFrame("Log out of OpenAI", onDismiss) {
        AuthenticationLogoutContent(source, onDismiss, onConfirm)
    }
}

@Composable
internal fun BoxScope.AuthenticationLogoutFrame(
    title: String, onDismiss: () -> Unit, content: @Composable () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 72)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(TuiTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Text(title, textStyle = TuiTheme.typography.headline)
            content()
        }
    }
}
