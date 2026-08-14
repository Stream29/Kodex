package io.github.stream29.kodex.desktop.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginEffect
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginState
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.desktop.components.DesktopModal
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl
import kotlinx.coroutines.flow.collect

/** Material Desktop browser sign-in dialog for one short-lived login ViewModel. */
@Composable
public fun OpenAiLoginDesktopDialog(
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
): Unit {
    val state by viewModel.state.collectAsState()
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
                is OpenAiLoginEffect.OpenExternalUrl ->
                    if (viewModel.isActive(effect.attemptId)) {
                        when (openExternalUrl(effect.url)) {
                            OpenExternalUrlResult.Started ->
                                viewModel.onBrowserOpened(effect.attemptId)

                            is OpenExternalUrlResult.Failed ->
                                viewModel.onBrowserOpenFailed(effect.attemptId)
                        }
                    }
            }
        }
    }

    DesktopModal(
        onDismissRequest = dismiss,
        modifier = Modifier.width(640.dp),
    ) {
        Surface(
            modifier = Modifier.width(640.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RectangleShape,
                ) {
                    Text(
                        text = "Sign in to OpenAI",
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LoginContent(
                    state = state,
                    viewModel = viewModel,
                    onDismissRequest = dismiss,
                )
            }
        }
    }
}

@Composable
private fun LoginContent(
    state: OpenAiLoginState,
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
): Unit {
    Column(Modifier.fillMaxWidth()) {
        when (state) {
            OpenAiLoginState.Ready -> {
                LoginMessage(
                    "Sign in with your ChatGPT subscription. Kodex will open a browser.",
                )
                LoginActions(
                    primaryLabel = "Open browser",
                    primaryAction = viewModel::start,
                    dismissLabel = "Cancel",
                    dismissAction = onDismissRequest,
                )
            }

            OpenAiLoginState.Preparing -> {
                LoginMessage("Preparing secure sign-in…")
                LoginActions(
                    dismissLabel = "Cancel",
                    dismissAction = onDismissRequest,
                )
            }

            is OpenAiLoginState.WaitingForAuthorization -> {
                LoginMessage("Waiting for browser sign-in…")
                LoginActions(
                    dismissLabel = "Cancel",
                    dismissAction = onDismissRequest,
                )
            }

            is OpenAiLoginState.BrowserOpenFailed -> {
                LoginMessage(
                    text = "Kodex could not open a browser.",
                    error = true,
                )
                LoginActions(
                    primaryLabel = "Retry browser",
                    primaryAction = { viewModel.retryBrowser(state.attemptId) },
                    dismissLabel = "Cancel",
                    dismissAction = onDismissRequest,
                )
            }

            OpenAiLoginState.Completed -> {
                LoginMessage(
                    "Sign-in complete. Kodex is now using its private credentials.",
                )
                LoginActions(
                    primaryLabel = "Close",
                    primaryAction = onDismissRequest,
                )
            }

            is OpenAiLoginState.Failed -> {
                LoginMessage(state.message, error = true)
                LoginActions(
                    primaryLabel = "Try again",
                    primaryAction = viewModel::start,
                    dismissLabel = "Cancel",
                    dismissAction = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun LoginMessage(
    text: String,
    error: Boolean = false,
): Unit {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RectangleShape,
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun LoginActions(
    primaryLabel: String? = null,
    primaryAction: (() -> Unit)? = null,
    dismissLabel: String? = null,
    dismissAction: (() -> Unit)? = null,
): Unit {
    require((primaryLabel == null) == (primaryAction == null)) {
        "A primary login label and action must be supplied together."
    }
    require((dismissLabel == null) == (dismissAction == null)) {
        "A dismiss login label and action must be supplied together."
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RectangleShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            if (primaryLabel != null) {
                TextButton(onClick = requireNotNull(primaryAction)) {
                    Text(primaryLabel)
                }
            }
            if (dismissLabel != null) {
                TextButton(onClick = requireNotNull(dismissAction)) {
                    Text(dismissLabel)
                }
            }
        }
    }
}
