package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.hooksettings.HookSettingsDialog
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel

@Composable
public actual fun BoxScope.HookSettingsComponent(viewModel: HookSettingsViewModel) {
    HookSettingsPanel(viewModel)
    HookSettingsDialogs(viewModel)
}

@Composable
public actual fun HookSettingsPanel(viewModel: HookSettingsViewModel, showOperationFailure: Boolean) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    Column {
        if (showOperationFailure && state.operationFailure) {
            SettingsErrorText("A settings operation failed.")
            SettingsActionButton(label = "Dismiss", onClick = viewModel::dismissFailure)
        }
        HookSettingsContent(
            hooks = state.hooks,
            onAdd = viewModel::add,
            onOpenDetails = { viewModel.details(it.name) },
        )
    }
}

@Composable
public actual fun BoxScope.HookSettingsDialogs(viewModel: HookSettingsViewModel) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    when (val dialog = state.dialog) {
        HookSettingsDialog.Hidden -> Unit
        is HookSettingsDialog.Details -> HookDetailsDialog(
            hook = dialog.hook,
            onDismiss = { viewModel.dismiss(dialog.token) },
            onEdit = { viewModel.edit(dialog.token) },
            onDelete = { viewModel.requestDelete(dialog.token) },
        )
        is HookSettingsDialog.Editing -> HookEditorDialog(
            editor = dialog,
            supportedTypes = state.supportedTypes,
            onDraftChange = { viewModel.updateDraft(dialog.token, it) },
            onDismiss = { viewModel.dismiss(dialog.token) },
            onSave = { viewModel.save(dialog.token) },
        )
        is HookSettingsDialog.Deleting -> HookDeleteConfirmationDialog(
            hook = dialog.original,
            onDismiss = { viewModel.dismiss(dialog.token) },
            onConfirm = { viewModel.confirmDelete(dialog.token) },
            error = dialog.error,
        )
    }
}
