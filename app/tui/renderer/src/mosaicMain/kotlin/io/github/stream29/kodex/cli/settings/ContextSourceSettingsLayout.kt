package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import io.github.stream29.kodex.cli.components.*

internal object MosaicContextSourceSettingsLayoutRenderer : ContextSourceSettingsLayoutRenderer {
    @Composable
    override fun ContextSourceCustomItem(
        path: String, checked: Boolean, onChange: (Boolean) -> Unit, onRemove: () -> Unit,
    ) {
        SettingsItem(path, supportingText = "Global context source") {
            Row {
                TuiCheckbox(
                    label = "Enabled", checked = checked, onCheckedChange = onChange,
                    color = SettingsForeground, idleTextStyle = TuiTheme.typography.body,
                    interactionStyle = TuiInteractionStyle.PreserveColors,
                )
                SettingsDangerButton("Remove", onClick = onRemove)
            }
        }
    }

    @Composable
    override fun ContextSourcePathInput(value: String, onChange: (String) -> Unit) {
        val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 96)
        val input = remember { TextInputState(TextInputValue(value)) }
        LaunchedEffect(value) {
            if (input.value.text != value) input.reset(TextInputValue(value, input.value.cursorOffset.coerceAtMost(value.length)))
        }
        TextInput(
            state = input, layout = TextInputLayout.create(input.value, width),
            modifier = Modifier.fillMaxWidth(), autoFocus = true,
            onValueChanged = { onChange(it.text) },
        )
    }

    @Composable
    override fun ContextSourceDialogActions(content: @Composable () -> Unit) {
        TuiDialogActionRow(Modifier.fillMaxWidth().background(SettingsActionBackground)) { content() }
    }

    @Composable
    override fun ContextSourceDialogAction(label: String, primary: Boolean, onClick: () -> Unit) {
        if (primary) SettingsPrimaryButton(label, onClick = onClick)
        else SettingsActionButton(label, onClick = onClick)
    }
}
