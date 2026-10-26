package io.github.stream29.kodex.cli.usagereset

import androidx.compose.runtime.*

private val LocalUsageResetLayoutRenderer = staticCompositionLocalOf<UsageResetLayoutRenderer> { error("Missing host UsageResetLayoutRenderer") }

@Composable
public actual fun WithUsageResetLayoutRenderer(renderer: UsageResetLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUsageResetLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun UsageResetOptionButton(label: String, enabled: Boolean, onClick: () -> Unit): Unit =
    LocalUsageResetLayoutRenderer.current.UsageResetOptionButton(label, enabled, onClick)

@Composable
internal actual fun UsageResetAction(label: String, primary: Boolean, autoFocus: Boolean, onClick: () -> Unit): Unit =
    LocalUsageResetLayoutRenderer.current.UsageResetAction(label, primary, autoFocus, onClick)

@Composable
internal actual fun UsageResetActions(content: @Composable () -> Unit): Unit =
    LocalUsageResetLayoutRenderer.current.UsageResetActions(content)
