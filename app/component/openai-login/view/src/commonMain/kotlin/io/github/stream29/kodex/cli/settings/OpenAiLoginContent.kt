package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginEffect
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginState
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.app.ui.UiProse

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
        LoginBodyFrame {
            when (val current = state) {
                OpenAiLoginState.Ready -> {
                    UiText("Sign in with your ChatGPT subscription. Kodex will open a browser.")
                    LoginActions("Open browser", viewModel::start, dismiss)
                }
                OpenAiLoginState.Preparing -> {
                    UiText("Preparing secure sign-in…")
                    LoginActions(onDismissRequest = dismiss)
                }
                is OpenAiLoginState.WaitingForAuthorization -> {
                    UiText("Waiting for browser sign-in…")
                    LoginActions(onDismissRequest = dismiss)
                }
                is OpenAiLoginState.BrowserOpenFailed -> {
                    UiText("Kodex could not open a browser.")
                    viewModel.manualAuthorizationUrl(current.attemptId)?.let {
                            UiText("Open this address in your browser:", UiTextRole.Supporting)
                            UiProse(it, UiTextRole.Supporting)
                        }
                    LoginActions("Retry browser", { viewModel.retryBrowser(current.attemptId) }, dismiss)
                }
                OpenAiLoginState.Completed -> {
                    UiText("Sign-in complete")
                    LoginActions("Close", dismiss)
                }
                is OpenAiLoginState.Failed -> {
                    UiText(current.message, UiTextRole.Error)
                    LoginActions("Try again", viewModel::start, dismiss)
                }
            }
        }
    }
}
