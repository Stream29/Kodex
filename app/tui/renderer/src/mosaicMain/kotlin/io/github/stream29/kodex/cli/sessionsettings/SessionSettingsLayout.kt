package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.settings.SettingsForeground
import io.github.stream29.kodex.cli.settings.SettingsHomeBackground

internal object MosaicSessionSettingsLayoutRenderer : SessionSettingsLayoutRenderer {
    @Composable
    override fun UnavailableSessionSettingsContent() {
        Text("No selected session", Modifier.fillMaxWidth().background(SettingsHomeBackground), color = SettingsForeground)
    }
}
