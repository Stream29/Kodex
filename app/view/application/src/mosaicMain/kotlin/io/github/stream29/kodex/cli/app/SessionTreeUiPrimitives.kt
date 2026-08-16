package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputEdit
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SubmitKey
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier

@Composable
internal fun ComposerInput(
    state: TextInputState,
    layout: TextInputLayout,
    newLineKey: NewLineKey,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    submitHint: String? = null,
    onSubmit: () -> Unit,
    onValueChanged: ((TextInputValue) -> Unit)? = null,
) {
    Column {
        TextInput(
            state = state,
            layout = layout,
            modifier = Modifier
                .fillMaxWidth(),
            autoFocus = autoFocus,
            enabled = enabled,
            onValueChanged = onValueChanged,
            onKeyEvent = { event ->
                when {
                    newLineKey.matches(event) -> {
                        if (state.edit(TextInputEdit.Insert("\n"))) {
                            onValueChanged?.invoke(state.value)
                        }
                        true
                    }

                    newLineKey.submitKey.matches(event) -> {
                        onSubmit()
                        true
                    }

                    else -> false
                }
            },
        )
        submitHint?.let { hint ->
            Text(value = hint, textStyle = TuiTheme.typography.supporting)
        }
    }
}

@Composable
internal fun HistoryComposerSeparator(
    columns: Int,
    showScrollToLatest: Boolean = false,
    onScrollToLatest: () -> Unit = {},
) {
    val width = columns.coerceAtLeast(1)
    if (!showScrollToLatest) {
        Text(
            "-".repeat(width),
            textStyle = TuiTheme.typography.supporting,
        )
        return
    }

    val separatorWidth = (width - ScrollToLatestButtonWidth).coerceAtLeast(0)
    val leadingWidth = (separatorWidth + 1) / 2
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("-".repeat(leadingWidth), textStyle = TuiTheme.typography.supporting)
        TuiButton(
            label = "↓",
            onClick = onScrollToLatest,
        )
        Text(
            "-".repeat(separatorWidth - leadingWidth),
            textStyle = TuiTheme.typography.supporting,
        )
    }
}

private const val ScrollToLatestButtonWidth: Int = 3

internal fun ReasoningEffort.displayName(): String = when (this) {
    ReasoningEffort.None -> "none"; ReasoningEffort.Minimal -> "minimal"; ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"; ReasoningEffort.High -> "high"; ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"; ReasoningEffort.Ultra -> "ultra"; is ReasoningEffort.Custom -> wireName
}

internal fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "default"; ServiceTier.Fast -> "fast"; ServiceTier.Flex -> "flex"
}

internal fun AgentMode.displayName(): String = when (this) {
    AgentMode.Single -> "single agent"; AgentMode.Multi -> "multi agent"
}

internal fun RequestUserInputMode.displayName(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

internal val SessionForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onPrimary

internal val SessionTopBarBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary

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
    get() = TuiTheme.colorScheme.primary

internal val SettingsDialogNavigationBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer

internal val SettingsDialogHomeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surface

internal val SettingsDialogNewLineBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHighest

internal val SettingsDialogSubmitKeyBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

internal val SettingsDialogModeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.secondaryContainer

internal val SettingsDialogActionBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primary
internal const val HistoryComposerSeparatorRows: Int = 1

private fun NewLineKey.matches(event: KeyEvent): Boolean = event.key == "Enter" && when (this) {
    NewLineKey.ShiftEnter -> event.shift && !event.ctrl && !event.alt
    NewLineKey.Enter -> !event.shift && !event.ctrl && !event.alt
}

private fun SubmitKey.matches(event: KeyEvent): Boolean = event.key == "Enter" && when (this) {
    SubmitKey.Enter -> !event.shift && !event.ctrl && !event.alt
    SubmitKey.CtrlEnter -> event.ctrl && !event.shift && !event.alt
}
