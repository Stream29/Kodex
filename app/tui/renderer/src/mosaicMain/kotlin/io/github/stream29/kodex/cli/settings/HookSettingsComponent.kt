package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

@Composable
public actual fun BoxScope.HookSettingsComponent(viewModel: HookSettingsViewModel) {
    HookSettingsPanel(viewModel)
    HookSettingsDialogs(viewModel)
}

@Composable
public actual fun HookSettingsPanel(viewModel: HookSettingsViewModel, showOperationFailure: Boolean) {
    HookSettingsPage(viewModel, showOperationFailure)
}

@Composable
public actual fun BoxScope.HookSettingsDialogs(viewModel: HookSettingsViewModel) {
    val renderer = remember(this) { MosaicHookDialogs(this) }
    HookSettingsDialogHost(viewModel, renderer)
}

internal class MosaicHookDialogs(private val box: BoxScope) : HookDialogRenderer {
    @Composable override fun Editor(
        editor: HookSettingsDialog.Editing, supportedTypes: Set<NotificationHookType>,
        onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
    ) { with(box) { HookEditorDialog(editor, supportedTypes, onDraftChange, onDismiss, onSave) } }
    @Composable override fun Details(hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
        with(box) { HookDetailsDialog(hook, onDismiss, onEdit, onDelete) }
    }
    @Composable override fun Deleting(hook: NotificationHook, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        with(box) { HookDeleteConfirmationDialog(hook, onDismiss, onConfirm, error) }
    }
}

@Composable
internal fun BoxScope.HookEditorDialog(
    editor: HookSettingsDialog.Editing, supportedTypes: Set<NotificationHookType> = NotificationHookType.entries.toSet(),
    onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
) {
    HookEditorContent(editor, supportedTypes, onDraftChange, onDismiss, onSave) { title, dismiss, content ->
        HookDialogFrame(title, 96, dismiss, content)
    }
}

@Composable
internal fun BoxScope.HookDetailsDialog(hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    HookDetailsContent(hook, onDismiss, onEdit, onDelete) { title, dismiss, content ->
        HookDialogFrame(title, 72, dismiss, content)
    }
}

@Composable
internal fun BoxScope.HookDeleteConfirmationDialog(
    hook: NotificationHook, onDismiss: () -> Unit, onConfirm: () -> Unit, error: String? = null,
) {
    HookDeleteContent(hook, error, onDismiss, onConfirm) { title, dismiss, content ->
        HookDialogFrame(title, 56, dismiss, content)
    }
}

@Composable
private fun BoxScope.HookDialogFrame(title: String, maximumWidth: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, maximumWidth)
    TuiDialog(onDismissRequest = onDismiss, modifier = Modifier.width(width).background(SettingsDialogBackground)) {
        Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                title, Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground, textStyle = TuiTheme.typography.headline,
            )
            content()
        }
    }
}
