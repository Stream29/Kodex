package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

/** Modal mounting only; each implementation wraps the same shared form in its renderer's frame. */
public interface HookDialogRenderer {
    @Composable
    public fun Editor(
        editor: HookSettingsDialog.Editing, supportedTypes: Set<NotificationHookType>,
        onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
        onDismiss: () -> Unit, onSave: () -> Unit,
    )
    @Composable
    public fun Details(hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit)
    @Composable
    public fun Deleting(hook: NotificationHook, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit)
}

@Composable
public expect fun HookSettingsPage(viewModel: HookSettingsViewModel, showOperationFailure: Boolean = true): Unit

/** Branches and all callbacks are bound to the displayed token, not the latest same-name Hook. */
@Composable
public expect fun HookSettingsDialogHost(viewModel: HookSettingsViewModel, renderer: HookDialogRenderer): Unit

@Composable
public expect fun HookSettingsContent(
    hooks: List<NotificationHook>, onAdd: () -> Unit, onOpenDetails: (NotificationHook) -> Unit,
): Unit

@Composable
public expect fun HookEditorContent(
    editor: HookSettingsDialog.Editing,
    supportedTypes: Set<NotificationHookType>,
    onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

@Composable
public expect fun HookDetailsContent(
    hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

@Composable
public expect fun HookDeleteContent(
    hook: NotificationHook, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

public enum class HookActionRole { Normal, Primary, Danger, ProminentDanger }
@Composable internal expect fun HookListFrame(content: @Composable () -> Unit)
@Composable internal expect fun HookListHeader(title: String, action: String, onAdd: () -> Unit)
@Composable internal expect fun HookListEntry(label: String, onClick: () -> Unit)
@Composable internal expect fun HookInput(label: String, value: String, autoFocus: Boolean = false, onChange: (String) -> Unit)
@Composable internal expect fun HookTypeChoice(label: String, checked: Boolean, onClick: () -> Unit)
@Composable internal expect fun HookActions(content: @Composable () -> Unit)
@Composable internal expect fun HookAction(
    label: String, role: HookActionRole = HookActionRole.Normal, autoFocus: Boolean = false, onClick: () -> Unit,
)
