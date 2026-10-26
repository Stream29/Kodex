package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface LoginLayoutRenderer {
    @Composable
    public fun LoginBodyFrame(content: @Composable () -> Unit): Unit

    @Composable
    public fun LoginActions(
    primaryLabel: String? = null,
    primaryAction: (() -> Unit)? = null,
    onDismissRequest: (() -> Unit)? = null,
): Unit
}

@Composable
public expect fun WithLoginLayoutRenderer(renderer: LoginLayoutRenderer, content: @Composable () -> Unit): Unit
