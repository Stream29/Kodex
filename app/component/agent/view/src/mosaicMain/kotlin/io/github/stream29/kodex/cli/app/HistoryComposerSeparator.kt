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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun HistoryComposerSeparator(
    columns: Int,
    liveDuration: Duration? = null,
    showScrollToLatest: Boolean = false,
    onScrollToLatest: () -> Unit = {},
) {
    val width = columns.coerceAtLeast(1)
    val label = liveDuration?.let(::liveTurnDurationLabel)
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

internal fun liveTurnDurationLabel(duration: Duration): String =
    "Worked for ${duration.roundToSeconds()}"

internal fun Duration.roundToSeconds(): Duration {
    if (!isFinite()) return this
    val truncatedSeconds = inWholeSeconds
    val truncated = truncatedSeconds.seconds
    val remainder = this - truncated
    val roundedSeconds = when {
        remainder >= 500.milliseconds -> truncatedSeconds + 1
        remainder <= (-500).milliseconds -> truncatedSeconds - 1
        else -> truncatedSeconds
    }
    return roundedSeconds.seconds
}

private const val ScrollToLatestButtonWidth: Int = 3
internal const val HistoryComposerSeparatorRows: Int = 1
