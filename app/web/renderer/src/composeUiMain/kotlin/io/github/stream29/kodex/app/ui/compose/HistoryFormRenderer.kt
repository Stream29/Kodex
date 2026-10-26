package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.stream29.kodex.cli.history.HistoryFormRenderer
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowModel
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowRole

@Composable
public actual fun rememberComposeHistoryFormRenderer(): HistoryFormRenderer = ComposeHistoryFormRenderer

private object ComposeHistoryFormRenderer : HistoryFormRenderer {
    @Composable override fun Frame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
    @Composable override fun Row(row: RequestUserInputHistoryRowModel) {
        SelectionContainer {
            Text(
                row.value, Modifier.fillMaxWidth(),
                fontWeight = if (row.role == RequestUserInputHistoryRowRole.Header) FontWeight.Bold else FontWeight.Normal,
                color = when (row.role) {
                    RequestUserInputHistoryRowRole.Error -> MaterialTheme.colorScheme.error
                    RequestUserInputHistoryRowRole.Supporting -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
