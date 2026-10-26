package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.UiText

internal object BrowserSessionSettingsLayoutRenderer : SessionSettingsLayoutRenderer {
    @Composable
    override fun UnavailableSessionSettingsContent() { UiText("No selected session") }
}
