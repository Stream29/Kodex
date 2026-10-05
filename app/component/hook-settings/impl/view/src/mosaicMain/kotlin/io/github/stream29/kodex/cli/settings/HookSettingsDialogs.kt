package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.hooksettings.HookSettingsDialog
import io.github.stream29.kodex.app.hooksettings.HookEditorDraft
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

/** Add/edit form for a frontend command and its selected notification types. */
@Composable
internal fun BoxScope.HookEditorDialog(
    editor: HookSettingsDialog.Editing,
    onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookEditorMaximumWidth)
    val draft = editor.draft
    val name = rememberHookInput(editor.token, draft.name)
    val command = rememberHookInput(editor.token, draft.command)

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = if (editor.originalName == null) "Add Hook" else "Edit Hook",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            HookInputField("Name", name, width, autoFocus = true) { name ->
                onDraftChange { it.copy(name = name) }
            }
            Text("Types:", color = SettingsForeground)
            NotificationHookType.entries.forEach { type ->
                SettingsContentButton(
                    label = "${if (type in draft.types) "[x]" else "[ ]"} ${type.settingsLabel()}",
                    onClick = { onDraftChange { current -> current.copy(types =
                        if (type in current.types) current.types - type else current.types + type,
                    ) } },
                )
            }
            HookInputField("Command", command, width) { command ->
                onDraftChange { it.copy(command = command) }
            }
            editor.error?.let { message ->
                SettingsErrorText(message)
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(label = "Cancel", onClick = onDismiss)
                SettingsPrimaryButton(label = "Save", onClick = onSave)
            }
        }
    }
}

/** Command-free Hook details and management commands. */
@Composable
internal fun BoxScope.HookDetailsDialog(
    hook: NotificationHook,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookDetailsMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = hook.name,
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text(
                value = "Types: ${hook.types.joinToString { it.settingsLabel() }}",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(label = "Close", onClick = onDismiss)
                SettingsActionButton(label = "Edit", onClick = onEdit)
                SettingsDangerButton(
                    label = "Delete",
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.HookDeleteConfirmationDialog(
    hook: NotificationHook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    error: String? = null,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookDeleteMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                "Delete Hook",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text("Delete '${hook.name}'?", color = SettingsForeground)
            error?.let { SettingsErrorText(it) }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(
                    label = "Cancel",
                    autoFocus = true,
                    onClick = onDismiss,
                )
                SettingsDangerButton(
                    label = "Delete",
                    prominent = true,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun HookInputField(
    label: String,
    state: TextInputState,
    width: Int,
    autoFocus: Boolean = false,
    onChange: (String) -> Unit,
) {
    Text(label, color = SettingsForeground)
    TextInput(
        state = state,
        layout = TextInputLayout.create(state.value, width),
        modifier = Modifier.fillMaxWidth(),
        autoFocus = autoFocus,
        onValueChanged = { onChange(it.text) },
    )
}

@Composable
private fun rememberHookInput(token: Any, text: String): TextInputState {
    val state = remember(token) {
        TextInputState(
            TextInputValue(
                text = text,
                cursorOffset = text.length,
            ),
        )
    }
    LaunchedEffect(text) {
        if (state.value.text != text) {
            state.reset(TextInputValue(text, state.value.cursorOffset.coerceAtMost(text.length)))
        }
    }
    return state
}

private const val HookEditorMaximumWidth: Int = 96
private const val HookDetailsMaximumWidth: Int = 72
private const val HookDeleteMaximumWidth: Int = 56
