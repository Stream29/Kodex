package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

private val LocalLoginLayoutRenderer = staticCompositionLocalOf<LoginLayoutRenderer> { error("Missing host LoginLayoutRenderer") }

@Composable
public actual fun WithLoginLayoutRenderer(renderer: LoginLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLoginLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun LoginBodyFrame(content: @Composable () -> Unit): Unit =
    LocalLoginLayoutRenderer.current.LoginBodyFrame(content)

@Composable
internal actual fun LoginActions(primaryLabel: String?, primaryAction: (() -> Unit)?, onDismissRequest: (() -> Unit)?): Unit =
    LocalLoginLayoutRenderer.current.LoginActions(primaryLabel, primaryAction, onDismissRequest)
