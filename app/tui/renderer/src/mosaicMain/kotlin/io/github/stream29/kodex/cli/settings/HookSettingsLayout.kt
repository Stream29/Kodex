package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.cli.components.*

internal object MosaicHookSettingsLayoutRenderer : HookSettingsLayoutRenderer {
    @Composable override fun HookListFrame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().background(SettingsHomeBackground)) { content() }
    }

    @Composable override fun HookListHeader(title: String, action: String, onAdd: () -> Unit) {
        Row(Modifier.fillMaxWidth().background(SettingsSectionHeaderBackground)) {
            Text("$title ", color = SettingsForeground, textStyle = TuiTheme.typography.title)
            SettingsActionButton(action, onClick = onAdd)
        }
    }

    @Composable override fun HookListEntry(label: String, onClick: () -> Unit) {
        SettingsContentButton(
            label, Modifier.fillMaxWidth(), idleTextStyle = TuiTheme.typography.body + TextStyle.Bold, onClick = onClick,
        )
    }

    @Composable override fun HookInput(label: String, value: String, autoFocus: Boolean, onChange: (String) -> Unit) {
        val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, 96)
        val input = remember { TextInputState(TextInputValue(value)) }
        LaunchedEffect(value) {
            if (input.value.text != value) input.reset(TextInputValue(value, input.value.cursorOffset.coerceAtMost(value.length)))
        }
        Text(label, color = SettingsForeground)
        TextInput(
            input, TextInputLayout.create(input.value, width), Modifier.fillMaxWidth(),
            autoFocus = autoFocus, onValueChanged = { onChange(it.text) },
        )
    }

    @Composable override fun HookTypeChoice(label: String, checked: Boolean, onClick: () -> Unit) {
        SettingsContentButton("${if (checked) "[x]" else "[ ]"} $label", onClick = onClick)
    }

    @Composable override fun HookActions(content: @Composable () -> Unit) {
        TuiDialogActionRow(Modifier.fillMaxWidth().background(SettingsActionBackground)) { content() }
    }

    @Composable override fun HookAction(label: String, role: HookActionRole, autoFocus: Boolean, onClick: () -> Unit) {
        when (role) {
            HookActionRole.Normal -> SettingsActionButton(label, autoFocus = autoFocus, onClick = onClick)
            HookActionRole.Primary -> SettingsPrimaryButton(label, autoFocus = autoFocus, onClick = onClick)
            HookActionRole.Danger -> SettingsDangerButton(label, autoFocus = autoFocus, onClick = onClick)
            HookActionRole.ProminentDanger -> SettingsDangerButton(label, prominent = true, autoFocus = autoFocus, onClick = onClick)
        }
    }
}
