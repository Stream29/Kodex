package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

/** Modal mounting only; each implementation wraps the same shared form in its renderer's frame. */
public interface HookDialogRenderer {
    @Composable
    public fun Editor(
        editor: HookSettingsDialog.Editing,
        supportedTypes: Set<NotificationHookType>,
        onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
        onDismiss: () -> Unit,
        onSave: () -> Unit,
    ): Unit

    @Composable
    public fun Details(
        hook: NotificationHook,
        onDismiss: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ): Unit

    @Composable
    public fun Deleting(
        hook: NotificationHook,
        error: String?,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit,
    ): Unit
}

@Composable
public expect fun HookSettingsPage(
    viewModel: HookSettingsViewModel,
    renderer: SettingsRenderer,
): Unit

/** Branches and all callbacks are bound to the displayed token, not the latest same-name Hook. */
@Composable
public expect fun HookSettingsDialogHost(
    viewModel: HookSettingsViewModel,
    renderer: HookDialogRenderer,
): Unit

@Composable
public expect fun HookSettingsContent(
    hooks: List<NotificationHook>,
    renderer: SettingsRenderer,
    onAdd: () -> Unit,
    onOpenDetails: (NotificationHook) -> Unit,
): Unit

@Composable
public expect fun HookEditorContent(
    editor: HookSettingsDialog.Editing,
    renderer: SettingsRenderer,
    supportedTypes: Set<NotificationHookType>,
    onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

@Composable
public expect fun HookDetailsContent(
    hook: NotificationHook,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit

@Composable
public expect fun HookDeleteContent(
    hook: NotificationHook,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
): Unit
