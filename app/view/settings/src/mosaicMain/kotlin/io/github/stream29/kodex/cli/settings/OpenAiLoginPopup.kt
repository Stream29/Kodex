package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginEffect
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginState
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl

/** Centered modal popup for one self-managed OpenAI browser sign-in attempt. */
@Composable
public fun BoxScope.OpenAiLoginPopup(
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val dialogWidth = (LocalTerminalState.current.size.columns - 4).coerceIn(1, LoginDialogMaximumWidth)
    val dismiss = {
        viewModel.cancel()
        onDismissRequest()
    }

    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OpenAiLoginEffect.OpenExternalUrl -> if (viewModel.isActive(effect.attemptId)) {
                    when (openExternalUrl(effect.url)) {
                        OpenExternalUrlResult.Started -> viewModel.onBrowserOpened(effect.attemptId)
                        is OpenExternalUrlResult.Failed -> viewModel.onBrowserOpenFailed(effect.attemptId)
                    }
                }
            }
        }
    }

    TuiDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.width(dialogWidth).background(LoginDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(LoginDialogBackground)) {
            Text(
                value = "Sign in to OpenAI",
                modifier = Modifier.fillMaxWidth().background(LoginDialogHeaderBackground),
                color = LoginDialogForeground,
                textStyle = TuiTheme.typography.headline,
            )
            LoginContent(
                state = state,
                viewModel = viewModel,
                onDismissRequest = dismiss,
            )
        }
    }
}

@Composable
private fun LoginContent(
    state: OpenAiLoginState,
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(LoginDialogBackground)) {
        when (state) {
            OpenAiLoginState.Ready -> {
                Text(
                    value = "Sign in with your ChatGPT subscription. Kodex will open a browser.",
                    color = LoginDialogForeground,
                )
                LoginActions(
                    primaryLabel = "Open browser",
                    primaryAction = viewModel::start,
                    onDismissRequest = onDismissRequest,
                )
            }

            OpenAiLoginState.Preparing -> {
                Text("Preparing secure sign-in…", color = LoginDialogForeground)
                LoginActions(onDismissRequest = onDismissRequest)
            }

            is OpenAiLoginState.WaitingForAuthorization -> {
                Text("Waiting for browser sign-in…", color = LoginDialogForeground)
                LoginActions(onDismissRequest = onDismissRequest)
            }

            is OpenAiLoginState.BrowserOpenFailed -> {
                Text("Kodex could not open a browser.", color = LoginDialogForeground)
                LoginActions(
                    primaryLabel = "Retry browser",
                    primaryAction = { viewModel.retryBrowser(state.attemptId) },
                    onDismissRequest = onDismissRequest,
                )
            }

            OpenAiLoginState.Completed -> {
                Text(
                    "Sign-in complete. Kodex is now using its private credentials.",
                    color = LoginDialogForeground,
                )
                LoginActions(
                    primaryLabel = "Close",
                    primaryAction = onDismissRequest,
                    onDismissRequest = null,
                )
            }

            is OpenAiLoginState.Failed -> {
                Text(state.message, color = SettingsErrorForeground)
                LoginActions(
                    primaryLabel = "Try again",
                    primaryAction = viewModel::start,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun LoginActions(
    primaryLabel: String? = null,
    primaryAction: (() -> Unit)? = null,
    onDismissRequest: (() -> Unit)? = null,
) {
    require((primaryLabel == null) == (primaryAction == null)) {
        "A primary label and action must be supplied together."
    }
    TuiDialogActionRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(LoginDialogActionBackground),
    ) {
        onDismissRequest?.let { dismiss ->
            SettingsActionButton(
                label = "Cancel",
                onClick = dismiss,
            )
        }
        primaryLabel?.let { label ->
            SettingsPrimaryButton(
                label = label,
                onClick = requireNotNull(primaryAction),
            )
        }
    }
}

private val LoginDialogForeground
    @Composable
    get() = SettingsForeground

private val LoginDialogBackground
    @Composable
    get() = SettingsDialogBackground

private val LoginDialogHeaderBackground
    @Composable
    get() = SettingsHeaderBackground

private val LoginDialogActionBackground
    @Composable
    get() = SettingsActionBackground

private const val LoginDialogMaximumWidth: Int = 72
