package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.hooksettings.HookSettingsDialog
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel

/**
 * Full Hook list and independent dialog renderer. Consume the exact stable Settings child inside
 * a TuiPopupHost; the host owns page selection and calls hidePage on leaving / close on disposal.
 * This renderer never creates a VM/store, captures a baseline or keeps business draft state.
 * For a scrollable Settings page use [HookSettingsPanel] in its viewport and
 * [HookSettingsDialogs] at the containing popup host instead.
 */
@Composable
public fun BoxScope.HookSettingsComponent(viewModel: HookSettingsViewModel) {
    HookSettingsPanel(viewModel)
    HookSettingsDialogs(viewModel)
}

/**
 * List/failure surface only; layout, focus and scrolling belong to its caller.
 * Set [showOperationFailure] false only when the host renders the shared failure
 * acknowledgement itself, so one application failure does not produce duplicate banners.
 */
@Composable
public fun HookSettingsPanel(viewModel: HookSettingsViewModel, showOperationFailure: Boolean = true) {
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

/** Overlay renderer; every callback is bound to its exact VM-owned dialog token. */
@Composable
public fun BoxScope.HookSettingsDialogs(viewModel: HookSettingsViewModel) {
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
