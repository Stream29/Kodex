package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.ui.UiColumn

internal object BrowserAuthenticationSettingsLayoutRenderer : AuthenticationSettingsLayoutRenderer {
    @Composable
    override fun AuthenticationSettingsFrame(content: @Composable () -> Unit) { UiColumn(content) }
}
