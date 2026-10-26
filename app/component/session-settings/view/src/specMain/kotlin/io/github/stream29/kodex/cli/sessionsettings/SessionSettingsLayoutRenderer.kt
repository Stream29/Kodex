package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface SessionSettingsLayoutRenderer {
    @Composable
    public fun UnavailableSessionSettingsContent(): Unit
}

@Composable
public expect fun WithSessionSettingsLayoutRenderer(renderer: SessionSettingsLayoutRenderer, content: @Composable () -> Unit): Unit
