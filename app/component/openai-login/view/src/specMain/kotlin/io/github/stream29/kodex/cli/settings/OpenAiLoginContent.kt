package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel

/**
 * Short-lived login child: unmount closes it. Launch effects are checked against the active attempt.
 * [openBrowser] returns launcher acceptance only, never authorization success; cancellation propagates.
 * Hosts supply their own launcher and modal frame, not a loopback listener or credential store.
 */
@Composable
public expect fun OpenAiLoginContent(
    viewModel: OpenAiLoginViewModel,
    onDismissRequest: () -> Unit,
    openBrowser: suspend (String) -> Boolean,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

@Composable
internal expect fun LoginBodyFrame(content: @Composable () -> Unit)

@Composable
internal expect fun LoginActions(
    primaryLabel: String? = null,
    primaryAction: (() -> Unit)? = null,
    onDismissRequest: (() -> Unit)? = null,
)
