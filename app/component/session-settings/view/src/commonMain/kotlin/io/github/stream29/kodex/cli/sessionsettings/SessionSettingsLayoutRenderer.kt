package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.*

private val LocalSessionSettingsLayoutRenderer = staticCompositionLocalOf<SessionSettingsLayoutRenderer> { error("Missing host SessionSettingsLayoutRenderer") }

@Composable
public actual fun WithSessionSettingsLayoutRenderer(renderer: SessionSettingsLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSessionSettingsLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun UnavailableSessionSettingsContent(): Unit =
    LocalSessionSettingsLayoutRenderer.current.UnavailableSessionSettingsContent()
