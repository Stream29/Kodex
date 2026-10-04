package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
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

internal fun ReasoningEffort.displayName(): String = when (this) {
    ReasoningEffort.None -> "none"; ReasoningEffort.Minimal -> "minimal"; ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"; ReasoningEffort.High -> "high"; ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"; is ReasoningEffort.Custom -> wireName
}

internal fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "default"; ServiceTier.Fast -> "fast"; ServiceTier.Flex -> "flex"
}

internal fun RequestUserInputMode.displayName(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

internal val SessionForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurface

internal val SessionButtonForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onPrimaryContainer

internal val SessionTopBarBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = Color.Unspecified

internal val SessionButtonBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primaryContainer

internal val PopupMenuBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

internal val SettingsDialogForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurface

internal val SettingsDialogHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

internal val SettingsDialogNavigationBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

internal val SettingsDialogHomeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

internal val SettingsDialogActionBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh
internal const val HistoryComposerSeparatorRows: Int = 1
