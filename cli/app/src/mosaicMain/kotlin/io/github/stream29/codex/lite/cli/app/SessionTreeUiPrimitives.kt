package io.github.stream29.codex.lite.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.codex.lite.cli.components.TextInput
import io.github.stream29.codex.lite.cli.components.TextInputEdit
import io.github.stream29.codex.lite.cli.components.TextInputLayout
import io.github.stream29.codex.lite.cli.components.TextInputState
import io.github.stream29.codex.lite.cli.components.TextInputValue
import io.github.stream29.codex.lite.cli.settings.NewLineKey
import io.github.stream29.codex.lite.cli.settings.SubmitKey
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ServiceTier

@Composable
internal fun ComposerInput(
    state: TextInputState,
    layout: TextInputLayout,
    newLineKey: NewLineKey,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    onSubmit: () -> Unit,
    onValueChanged: ((TextInputValue) -> Unit)? = null,
) {
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
}

@Composable
internal fun HistoryComposerSeparator(columns: Int) = Text(
    "-".repeat(columns.coerceAtLeast(1)), textStyle = TextStyle.Dim,
)

internal fun ReasoningEffort.displayName(): String = when (this) {
    ReasoningEffort.None -> "none"; ReasoningEffort.Minimal -> "minimal"; ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"; ReasoningEffort.High -> "high"; ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"; ReasoningEffort.Ultra -> "ultra"; is ReasoningEffort.Custom -> wireName
}
internal fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "default"; ServiceTier.Fast -> "fast"; ServiceTier.Flex -> "flex"
}
internal fun ModeKind.displayName(): String = when (this) { ModeKind.Default -> "default"; ModeKind.Plan -> "plan" }

internal enum class SettingsRoute(val label: String) { Global("Global"), Session("Session"), NewSession("New session") }

internal val SessionForeground: Color = Color.White
internal val SessionTopBarBackground: Color = Color(28, 68, 74)
internal val SessionButtonBackground: Color = Color(36, 78, 84)
internal val PopupMenuBackground: Color = Color(42, 42, 46)
internal val SettingsDialogForeground: Color = Color.White
internal val SettingsDialogHeaderBackground: Color = Color(28, 68, 74)
internal val SettingsDialogNavigationBackground: Color = Color(42, 42, 46)
internal val SettingsDialogSelectionBackground: Color = Color(36, 78, 84)
internal val SettingsDialogHomeBackground: Color = Color(52, 52, 56)
internal val SettingsDialogNewLineBackground: Color = Color(62, 62, 66)
internal val SettingsDialogSubmitKeyBackground: Color = Color(58, 58, 64)
internal val SettingsDialogModeBackground: Color = Color(46, 58, 62)
internal val SettingsDialogActionBackground: Color = Color(28, 68, 74)
internal const val SettingsDialogNavigationWidth: Int = 18
internal const val HistoryComposerSeparatorRows: Int = 1

private fun NewLineKey.matches(event: KeyEvent): Boolean = event.key == "Enter" && when (this) {
    NewLineKey.ShiftEnter -> event.shift && !event.ctrl && !event.alt
    NewLineKey.Enter -> !event.shift && !event.ctrl && !event.alt
}

private fun SubmitKey.matches(event: KeyEvent): Boolean = event.key == "Enter" && when (this) {
    SubmitKey.Enter -> !event.shift && !event.ctrl && !event.alt
    SubmitKey.CtrlEnter -> event.ctrl && !event.shift && !event.alt
}
