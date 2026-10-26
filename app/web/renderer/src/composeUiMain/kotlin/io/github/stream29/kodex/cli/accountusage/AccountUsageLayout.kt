package io.github.stream29.kodex.cli.accountusage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal object BrowserAccountUsageLayoutRenderer : AccountUsageLayoutRenderer {
    @Composable
    override fun AccountUsageFrame(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }

    @Composable
    override fun AccountUsageActions(refresh: @Composable () -> Unit, reset: @Composable () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { refresh(); reset() }
    }
}
