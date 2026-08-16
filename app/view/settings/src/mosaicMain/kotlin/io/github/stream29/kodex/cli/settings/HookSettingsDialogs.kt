package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.hook.contract.HookDraft
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.hook.contract.HookType

internal data class HookEditorRequest(
    val name: String? = null,
    val draft: HookDraft? = null,
)

/** Add/edit form for one name, type, and command Hook definition. */
@Composable
internal fun BoxScope.HookEditorDialog(
    request: HookEditorRequest,
    onDismiss: () -> Unit,
    onSave: (HookDraft) -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookEditorMaximumWidth)
    val initial = request.draft
    val name = rememberHookInput(initial?.name.orEmpty())
    val command = rememberHookInput(initial?.command.orEmpty())
    var type by remember(request) { mutableStateOf(initial?.type ?: HookType.PreToolUse) }
    var error by remember(request) { mutableStateOf<String?>(null) }

    fun save() {
        runCatching {
            val normalizedName = name.value.text.trim()
            require(normalizedName.isNotEmpty()) { "Hook name is required." }
            require(command.value.text.isNotBlank()) { "Command is required." }
            HookDraft(
                name = normalizedName,
                type = type,
                command = command.value.text,
            )
        }.fold(
            onSuccess = onSave,
            onFailure = { failure ->
                error = failure.message ?: "The Hook configuration is invalid."
            },
        )
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                value = if (request.name == null) "Add Hook" else "Edit Hook",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            HookInputField("Name", name, width, autoFocus = true)
            Row {
                Text("Type: ", color = SettingsForeground)
                TuiButton(
                    label = type.settingsLabel(),
                    color = SettingsForeground,
                    onClick = { type = type.next() },
                )
            }
            HookInputField("Command", command, width)
            error?.let { message ->
                Text(message, color = SettingsForeground, textStyle = TextStyle.Bold)
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Cancel", color = SettingsForeground, onClick = onDismiss)
                TuiButton(label = "Save", color = SettingsForeground, onClick = ::save)
            }
        }
    }
}

/** Command-free Hook details and management commands. */
@Composable
internal fun BoxScope.HookDetailsDialog(
    hook: HookManagedState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookDetailsMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                value = hook.name,
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text(
                value = "Type: ${hook.type.settingsLabel()}",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Close", color = SettingsForeground, onClick = onDismiss)
                TuiButton(label = "Edit", color = SettingsForeground, onClick = onEdit)
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.HookDeleteConfirmationDialog(
    hook: HookManagedState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookDeleteMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                "Delete Hook",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text("Delete '${hook.name}'?", color = SettingsForeground)
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(
                    label = "Cancel",
                    color = SettingsForeground,
                    autoFocus = true,
                    onClick = onDismiss,
                )
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
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
) {
    Text(label, color = SettingsForeground)
    TextInput(
        state = state,
        layout = TextInputLayout.create(state.value, width),
        modifier = Modifier.fillMaxWidth(),
        autoFocus = autoFocus,
    )
}

@Composable
private fun rememberHookInput(initialValue: String = ""): TextInputState =
    remember(initialValue) {
        TextInputState(
            TextInputValue(
                text = initialValue,
                cursorOffset = initialValue.length,
            ),
        )
    }

private fun HookType.next(): HookType =
    HookType.entries[(ordinal + 1) % HookType.entries.size]

private const val HookEditorMaximumWidth: Int = 96
private const val HookDetailsMaximumWidth: Int = 72
private const val HookDeleteMaximumWidth: Int = 56
