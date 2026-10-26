package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import kotlin.time.Duration

@Composable
internal fun HistoryComposerSeparator(
    columns: Int,
    liveDuration: Duration? = null,
    showScrollToLatest: Boolean = false,
    onScrollToLatest: () -> Unit = {},
) {
    AgentHistorySeparatorContent(
        liveDuration, showScrollToLatest, onScrollToLatest, MosaicHistorySeparatorRenderer(columns),
    )
}

internal class MosaicHistorySeparatorRenderer(private val columns: Int) : AgentHistorySeparatorRenderer {
    @Composable
    override fun Separator(label: String?, showScrollToLatest: Boolean, onScrollToLatest: () -> Unit) {
        val width = columns.coerceAtLeast(1)
        val reservedButtonWidth = if (showScrollToLatest) ScrollToLatestButtonWidth else 0
        val labelWidth = if (showScrollToLatest) {
            ((width - reservedButtonWidth) / 2).coerceAtLeast(0)
        } else {
            width
        }
        val displayedLabel = label?.ellipsizeToTerminalWidth(labelWidth)
        Box(modifier = Modifier.fillMaxWidth()) {
            Text("-".repeat(width), textStyle = TuiTheme.typography.supporting)
            displayedLabel?.let { value ->
                Text(value, textStyle = TuiTheme.typography.supporting)
            }
            if (showScrollToLatest) {
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TuiButton(
                        label = "↓",
                        idleTextStyle = TuiTheme.typography.supporting,
                        onClick = onScrollToLatest,
                    )
                }
            }
        }
    }
}

private const val ScrollToLatestButtonWidth: Int = 3
internal const val HistoryComposerSeparatorRows: Int = 1
