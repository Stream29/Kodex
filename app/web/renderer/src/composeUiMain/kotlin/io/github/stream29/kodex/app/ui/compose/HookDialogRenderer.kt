package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.EditorDialog as Dialog
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

@Composable
public actual fun rememberComposeHookDialogRenderer(): HookDialogRenderer = remember { ComposeHookDialogs() }

private class ComposeHookDialogs : HookDialogRenderer {
    @Composable override fun Editor(
        editor: HookSettingsDialog.Editing, supportedTypes: Set<NotificationHookType>,
        onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
    ) {
        HookEditorContent(editor, supportedTypes, onDraftChange, onDismiss, onSave) { title, dismiss, content ->
            HookDialogFrame(title, dismiss, content)
        }
    }
    @Composable override fun Details(hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
        HookDetailsContent(hook, onDismiss, onEdit, onDelete) { title, dismiss, content ->
            HookDialogFrame(title, dismiss, content)
        }
    }
    @Composable override fun Deleting(hook: NotificationHook, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        HookDeleteContent(hook, error, onDismiss, onConfirm) { title, dismiss, content ->
            HookDialogFrame(title, dismiss, content)
        }
    }
}

@Composable
private fun HookDialogFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.semantics { paneTitle = title },
            shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.widthIn(max = 720.dp).heightIn(max = 640.dp)
                    .semantics { contentDescription = "Hook dialog content" }.verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UiText(title, UiTextRole.Title)
                content()
            }
        }
    }
}
