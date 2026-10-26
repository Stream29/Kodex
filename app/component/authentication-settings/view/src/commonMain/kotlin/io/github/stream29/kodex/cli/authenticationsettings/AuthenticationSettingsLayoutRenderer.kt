package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.*

private val LocalAuthenticationSettingsLayoutRenderer = staticCompositionLocalOf<AuthenticationSettingsLayoutRenderer> { error("Missing host AuthenticationSettingsLayoutRenderer") }

@Composable
public actual fun WithAuthenticationSettingsLayoutRenderer(renderer: AuthenticationSettingsLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAuthenticationSettingsLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun AuthenticationSettingsFrame(content: @Composable () -> Unit): Unit =
    LocalAuthenticationSettingsLayoutRenderer.current.AuthenticationSettingsFrame(content)
