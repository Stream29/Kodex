package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.cli.components.*

internal object MosaicMcpSettingsLayoutRenderer : McpSettingsLayoutRenderer {
    @Composable override fun McpListFrame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().background(SettingsHomeBackground)) { content() }
    }
    @Composable override fun McpListHeader(title: String, add: String, importLabel: String, onAdd: () -> Unit, onImport: () -> Unit) {
        Row(Modifier.fillMaxWidth().background(SettingsSectionHeaderBackground)) {
            Text("$title ", color = SettingsForeground, textStyle = TuiTheme.typography.title)
            SettingsActionButton(add, onClick = onAdd)
            Text(" ")
            SettingsActionButton(importLabel, onClick = onImport)
        }
    }
    @Composable override fun McpListEntry(label: String, onClick: () -> Unit) {
        SettingsContentButton(label, Modifier.fillMaxWidth(), idleTextStyle = TuiTheme.typography.body + TextStyle.Bold, onClick = onClick)
    }
    @Composable override fun McpInput(label: String, token: Any, value: String, autoFocus: Boolean, onChange: (String) -> Unit) {
        val input = remember(token) { TextInputState(TextInputValue(value)) }
        val focus = remember(token) { FocusRequester() }
        val columns = LocalTerminalState.current.size.columns.coerceAtLeast(1)
        LaunchedEffect(value) {
            if (input.value.text != value) input.reset(TextInputValue(value, input.value.cursorOffset.coerceAtMost(value.length)))
        }
        Text(label, color = SettingsForeground)
        SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
            val width = minOf(constraints.maxWidth, columns).coerceAtLeast(1)
            val nodes = subcompose(token) {
                TextInput(
                    input, TextInputLayout.create(input.value, width), Modifier.fillMaxWidth(),
                    focusRequester = focus, autoFocus = autoFocus, onValueChanged = { onChange(it.text) },
                )
                LaunchedEffect(autoFocus, focus) {
                    if (autoFocus) { withFrameNanos { }; focus.requestFocus() }
                }
            }.map { it.measure(constraints) }
            layout(nodes.maxOfOrNull { it.width } ?: 0, nodes.maxOfOrNull { it.height } ?: 0) {
                nodes.forEach { it.place(0, 0) }
            }
        }
    }
    @Composable override fun McpActions(content: @Composable () -> Unit) {
        TuiDialogActionRow(Modifier.fillMaxWidth().background(SettingsActionBackground)) { content() }
    }
    @Composable override fun McpInlineActions(first: @Composable () -> Unit, second: @Composable () -> Unit) {
        Row { first(); Text(" "); second() }
    }
    @Composable override fun McpImportEntry(label: String, enabled: Boolean, onClick: () -> Unit) {
        SettingsContentButton(label, Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
    }
    @Composable override fun McpAction(label: String, role: McpActionRole, enabled: Boolean, autoFocus: Boolean, onClick: () -> Unit) {
        when (role) {
            McpActionRole.Normal -> SettingsActionButton(label, enabled = enabled, autoFocus = autoFocus, onClick = onClick)
            McpActionRole.Primary -> SettingsPrimaryButton(label, enabled = enabled, autoFocus = autoFocus, onClick = onClick)
            McpActionRole.Danger -> SettingsDangerButton(label, enabled = enabled, autoFocus = autoFocus, onClick = onClick)
            McpActionRole.ProminentDanger -> SettingsDangerButton(label, prominent = true, enabled = enabled, autoFocus = autoFocus, onClick = onClick)
        }
    }
}
