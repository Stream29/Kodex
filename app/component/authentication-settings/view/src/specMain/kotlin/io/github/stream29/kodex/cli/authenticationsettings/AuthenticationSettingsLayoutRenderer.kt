package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface AuthenticationSettingsLayoutRenderer {
    @Composable
    public fun AuthenticationSettingsFrame(content: @Composable () -> Unit): Unit
}

@Composable
public expect fun WithAuthenticationSettingsLayoutRenderer(renderer: AuthenticationSettingsLayoutRenderer, content: @Composable () -> Unit): Unit
