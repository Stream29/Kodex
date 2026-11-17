package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType

@Composable
public actual fun HookSettingsPage(viewModel: HookSettingsViewModel, renderer: SettingsRenderer) {
    val state = viewModel.state.collectAsState().value
    if (state.closed) return
    key(viewModel) {
        HookSettingsContent(state.hooks, renderer, viewModel::add) { viewModel.details(it.name) }
    }
}

@Composable
public actual fun HookSettingsContent(
    hooks: List<NotificationHook>,
    renderer: SettingsRenderer,
    onAdd: () -> Unit,
    onOpenDetails: (NotificationHook) -> Unit,
) {
    UiColumn {
        renderer.SectionHeader("Hooks") {
            renderer.Action("Add", onClick = onAdd, enabled = true, autoFocus = false, role = UiActionRole.Secondary)
        }
        if (hooks.isEmpty()) UiText("None configured", UiTextRole.Supporting)
        hooks.forEach { hook ->
            key(hook.name) {
                renderer.Entry("${hook.name} ${hook.types.joinToString { it.settingsLabel() }}", enabled = true) {
                    onOpenDetails(hook)
                }
            }
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
    editor: HookSettingsDialog.Editing,
    renderer: SettingsRenderer,
    supportedTypes: Set<NotificationHookType>,
    onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    frame: @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit,
) {
    key(editor.token) {
        val draft = editor.draft
        frame(if (editor.originalName == null) "Add Hook" else "Edit Hook", onDismiss) {
            renderer.TextField(editor.token, "Name", draft.name, autoFocus = true) { value ->
                onDraftChange { it.copy(name = value) }
            }
            UiText("Types:")
            NotificationHookType.entries.filter { it in supportedTypes }.forEach { type ->
                key(type) {
                    renderer.Toggle(
                        label = type.settingsLabel(),
                        checked = type in draft.types,
                        onChange = {
                            onDraftChange { current ->
                                current.copy(types = if (type in current.types) current.types - type else current.types + type)
                            }
                        },
                        enabled = true,
                        supportingText = null,
                    )
                }
            }
            renderer.TextField(editor.token, "Command", draft.command, autoFocus = false) { value ->
                onDraftChange { it.copy(command = value) }
            }
            editor.error?.let { UiText(it, UiTextRole.Error) }
            UiActions {
                UiAction("Cancel", onClick = onDismiss)
                UiAction("Save", onClick = onSave, role = UiActionRole.Primary)
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
        UiActions {
            UiAction("Close", onClick = onDismiss)
            UiAction("Edit", onClick = onEdit)
            UiAction("Delete", onClick = onDelete, role = UiActionRole.Destructive)
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
        UiActions {
            UiAction("Cancel", onClick = onDismiss, autoFocus = true)
            UiAction("Delete", onClick = onConfirm, role = UiActionRole.Destructive)
        }
    }
}

internal fun NotificationHookType.settingsLabel(): String = when (this) {
    NotificationHookType.StopAssistantMessage -> "Assistant message"
    NotificationHookType.AskUserCreated -> "Ask user created"
    NotificationHookType.StopUnhandledError -> "Unhandled error"
}
