package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.hooksettings.HookDialogToken
import io.github.stream29.kodex.app.hooksettings.HookEditorDraft
import io.github.stream29.kodex.app.hooksettings.HookSettingsDialog
import io.github.stream29.kodex.app.ui.compose.rememberComposeHookDialogRenderer
import io.github.stream29.kodex.rpc.models.NotificationHookType

/** Uses the production scrollable dialog and fields, without persisting or executing hooks. */
@Composable
internal fun HookInputSmoke(onClose: () -> Unit) {
    var editor by remember {
        mutableStateOf(HookSettingsDialog.Editing(HookDialogToken(), null, HookEditorDraft()))
    }
    rememberComposeHookDialogRenderer().Editor(
        editor = editor,
        supportedTypes = NotificationHookType.entries.toSet(),
        onDraftChange = { update -> editor = editor.copy(draft = update(editor.draft)) },
        onDismiss = onClose,
        onSave = onClose,
    )
}
