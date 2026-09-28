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
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

internal data class HookEditorRequest(
    val name: String? = null,
    val draft: NotificationHook? = null,
)

/** Add/edit form for a frontend command and its selected notification types. */
@Composable
internal fun BoxScope.HookEditorDialog(
    request: HookEditorRequest,
    onDismiss: () -> Unit,
    onSave: (NotificationHook) -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookEditorMaximumWidth)
    val initial = request.draft
    val name = rememberHookInput(initial?.name.orEmpty())
    val command = rememberHookInput(initial?.command.orEmpty())
    var types by remember(request) { mutableStateOf(initial?.types ?: NotificationHookType.entries.toSet()) }
    var error by remember(request) { mutableStateOf<String?>(null) }

    fun save() {
        runCatching {
            val normalizedName = name.value.text.trim()
            require(normalizedName.isNotEmpty()) { "Hook name is required." }
            require(command.value.text.isNotBlank()) { "Command is required." }
            NotificationHook(
                name = normalizedName,
                types = types,
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
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = if (request.name == null) "Add Hook" else "Edit Hook",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            HookInputField("Name", name, width, autoFocus = true)
            Text("Types:", color = SettingsForeground)
            NotificationHookType.entries.forEach { type ->
                SettingsContentButton(
                    label = "${if (type in types) "[x]" else "[ ]"} ${type.settingsLabel()}",
                    onClick = { types = if (type in types) types - type else types + type },
                )
            }
            HookInputField("Command", command, width)
            error?.let { message ->
                SettingsErrorText(message)
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(label = "Cancel", onClick = onDismiss)
                SettingsPrimaryButton(label = "Save", onClick = ::save)
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

private const val HookEditorMaximumWidth: Int = 96
private const val HookDetailsMaximumWidth: Int = 72
private const val HookDeleteMaximumWidth: Int = 56
