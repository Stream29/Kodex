package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

@Composable
public actual fun HookSettingsPage(viewModel: HookSettingsViewModel, showOperationFailure: Boolean) {
    val state = viewModel.state.collectAsState().value
    if (state.closed) return
    key(viewModel) { UiColumn {
        if (showOperationFailure && state.operationFailure) {
            UiText("A settings operation failed.", UiTextRole.Error)
            HookAction("Dismiss", onClick = viewModel::dismissFailure)
        }
        HookSettingsContent(state.hooks, viewModel::add) { viewModel.details(it.name) }
    } }
}

@Composable
public actual fun HookSettingsContent(
    hooks: List<NotificationHook>, onAdd: () -> Unit, onOpenDetails: (NotificationHook) -> Unit,
) {
    HookListFrame {
        HookListHeader("Hooks", "Add", onAdd)
        if (hooks.isEmpty()) UiText("None configured", UiTextRole.Supporting)
        hooks.forEach { hook ->
            key(hook.name) { HookListEntry("${hook.name} ${hook.types.joinToString { it.settingsLabel() }}") { onOpenDetails(hook) } }
        }
    }
}

@Composable
public actual fun HookSettingsDialogHost(viewModel: HookSettingsViewModel, renderer: HookDialogRenderer) {
    val state = viewModel.state.collectAsState().value
    if (state.closed) return
    when (val dialog = state.dialog) {
        HookSettingsDialog.Hidden -> Unit
        is HookSettingsDialog.Details -> key(viewModel, dialog.token) {
            renderer.Details(dialog.hook, { viewModel.dismiss(dialog.token) }, { viewModel.edit(dialog.token) }, { viewModel.requestDelete(dialog.token) })
        }
        is HookSettingsDialog.Editing -> key(viewModel, dialog.token) {
            renderer.Editor(
                dialog, state.supportedTypes, { viewModel.updateDraft(dialog.token, it) },
                { viewModel.dismiss(dialog.token) }, { viewModel.save(dialog.token) },
            )
        }
        is HookSettingsDialog.Deleting -> key(viewModel, dialog.token) {
            renderer.Deleting(dialog.original, dialog.error, { viewModel.dismiss(dialog.token) }, { viewModel.confirmDelete(dialog.token) })
        }
    }
}

@Composable
public actual fun HookEditorContent(
    editor: HookSettingsDialog.Editing, supportedTypes: Set<NotificationHookType>,
    onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    key(editor.token) {
        val draft = editor.draft
        frame(if (editor.originalName == null) "Add Hook" else "Edit Hook", onDismiss) {
            HookInput("Name", draft.name, autoFocus = true) { value -> onDraftChange { it.copy(name = value) } }
            UiText("Types:")
            NotificationHookType.entries.filter { it in supportedTypes }.forEach { type ->
                key(type) {
                    HookTypeChoice(type.settingsLabel(), type in draft.types) {
                        onDraftChange { current -> current.copy(
                            types = if (type in current.types) current.types - type else current.types + type,
                        ) }
                    }
                }
            }
            HookInput("Command", draft.command) { value -> onDraftChange { it.copy(command = value) } }
            editor.error?.let { UiText(it, UiTextRole.Error) }
            HookActions {
                HookAction("Cancel", onClick = onDismiss)
                HookAction("Save", HookActionRole.Primary, onClick = onSave)
            }
        }
    }
}

@Composable
public actual fun HookDetailsContent(
    hook: NotificationHook, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    frame(hook.name, onDismiss) {
        UiText("Types: ${hook.types.joinToString { it.settingsLabel() }}", UiTextRole.Supporting)
        HookActions {
            HookAction("Close", onClick = onDismiss)
            HookAction("Edit", onClick = onEdit)
            HookAction("Delete", HookActionRole.Danger, onClick = onDelete)
        }
    }
}

@Composable
public actual fun HookDeleteContent(
    hook: NotificationHook, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    frame("Delete Hook", onDismiss) {
        UiText("Delete '${hook.name}'?")
        error?.let { UiText(it, UiTextRole.Error) }
        HookActions {
            HookAction("Cancel", autoFocus = true, onClick = onDismiss)
            HookAction("Delete", HookActionRole.ProminentDanger, onClick = onConfirm)
        }
    }
}

internal fun NotificationHookType.settingsLabel(): String = when (this) {
    NotificationHookType.StopAssistantMessage -> "Assistant message"
    NotificationHookType.StopRequestUserInput -> "Request user input"
    NotificationHookType.StopSuggestSubagent -> "Suggest subagent"
    NotificationHookType.StopUnhandledError -> "Unhandled error"
}
