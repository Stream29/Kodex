package io.github.stream29.kodex.cli.accountusage

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.TuiTheme

internal object MosaicAccountUsageLayoutRenderer : AccountUsageLayoutRenderer {
    @Composable
    override fun AccountUsageFrame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surface)) { content() }
    }

    @Composable
    override fun AccountUsageActions(refresh: @Composable () -> Unit, reset: @Composable () -> Unit) {
        Row { refresh(); Text(" "); reset() }
    }
}
