package io.github.stream29.kodex.cli.authenticationsettings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.TuiTheme

internal object MosaicAuthenticationSettingsLayoutRenderer : AuthenticationSettingsLayoutRenderer {
    @Composable
    override fun AuthenticationSettingsFrame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surface)) { content() }
    }
}
