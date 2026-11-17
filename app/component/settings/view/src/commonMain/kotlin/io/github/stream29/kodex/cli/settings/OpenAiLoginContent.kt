package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginEffect
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginState
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiActionRole
import io.github.stream29.kodex.app.ui.UiActions
import io.github.stream29.kodex.app.ui.UiColumn
import io.github.stream29.kodex.app.ui.UiProse
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole

@Composable
public actual fun OpenAiLoginContent(
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
    openBrowser: suspend (String) -> Boolean,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val currentOpenBrowser by rememberUpdatedState(openBrowser)
    val dismiss = { viewModel.cancel(); onDismissRequest() }
    DisposableEffect(viewModel) { onDispose(viewModel::close) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OpenAiLoginEffect.OpenExternalUrl -> if (viewModel.isActive(effect.attemptId)) {
                    val opened = try {
                        currentOpenBrowser(effect.url)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                    if (opened) {
                        viewModel.onBrowserOpened(effect.attemptId)
                    } else {
                        viewModel.onBrowserOpenFailed(effect.attemptId)
                    }
                }
            }
        }
    }
    frame("Sign in to OpenAI", dismiss) {
        UiColumn {
            when (val current = state) {
                OpenAiLoginState.Ready -> {
                    UiText("Sign in with your ChatGPT subscription. Kodex will open a browser.")
                    UiActions {
                        UiAction("Cancel", onClick = dismiss)
                        UiAction("Open browser", onClick = viewModel::start, role = UiActionRole.Primary)
                    }
                }
                OpenAiLoginState.Preparing -> {
                    UiText("Preparing secure sign-in…")
                    UiActions { UiAction("Cancel", onClick = dismiss) }
                }
                is OpenAiLoginState.WaitingForAuthorization -> {
                    UiText("Waiting for browser sign-in…")
                    UiActions { UiAction("Cancel", onClick = dismiss) }
                }
                is OpenAiLoginState.BrowserOpenFailed -> {
                    UiText("Kodex could not open a browser.")
                    viewModel.manualAuthorizationUrl(current.attemptId)?.let {
                        UiText("Open this address in your browser:", UiTextRole.Supporting)
                        UiProse(it, UiTextRole.Supporting)
                    }
                    UiActions {
                        UiAction("Cancel", onClick = dismiss)
                        UiAction("Retry browser", onClick = { viewModel.retryBrowser(current.attemptId) }, role = UiActionRole.Primary)
                    }
                }
                OpenAiLoginState.Completed -> {
                    UiText("Sign-in complete")
                    UiActions { UiAction("Close", onClick = dismiss, role = UiActionRole.Primary) }
                }
                is OpenAiLoginState.Failed -> {
                    UiText(current.message, UiTextRole.Error)
                    UiActions {
                        UiAction("Cancel", onClick = dismiss)
                        UiAction("Try again", onClick = viewModel::start, role = UiActionRole.Primary)
                    }
                }
            }
        }
    }
}
