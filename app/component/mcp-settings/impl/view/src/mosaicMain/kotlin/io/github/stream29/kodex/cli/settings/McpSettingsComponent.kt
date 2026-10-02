package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog
import io.github.stream29.kodex.app.mcpsettings.McpSettingsEffect
import io.github.stream29.kodex.app.mcpsettings.McpSettingsViewModel
import kotlinx.coroutines.CancellationException

/**
 * Complete MCP list/dialog renderer inside a TuiPopupHost. Uses the stable child directly; no
 * business draft, preview selection or parallel route is remembered. Host calls hidePage when
 * leaving MCP, close when Settings ends, and installs [McpSettingsEffects] for the ENTIRE Settings
 * lifetime (even while another page is visible). No backend/store/platform opener is created here.
 * For host viewport layout, compose [McpSettingsPanel] and [McpSettingsDialogs] separately.
 */
@Composable
public fun BoxScope.McpSettingsComponent(viewModel: McpSettingsViewModel) {
    McpSettingsPanel(viewModel)
    McpSettingsDialogs(viewModel)
}

/**
 * List/failure surface; layout/scroll are renderer-local, runtime rows remain read-only.
 * Set [showOperationFailure] false only when the host renders the same shared failure
 * acknowledgement itself; this avoids duplicate banners without changing failure ownership.
 */
@Composable
public fun McpSettingsPanel(viewModel: McpSettingsViewModel, showOperationFailure: Boolean = true) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    Column {
        if (showOperationFailure && state.operationFailure) {
            SettingsErrorText("A settings operation failed.")
            SettingsActionButton(label = "Dismiss", onClick = viewModel::dismissFailure)
        }
        McpSettingsContent(
            servers = state.servers,
            onAdd = viewModel::add,
            onOpenDetails = { viewModel.details(it.serverName) },
            onImport = viewModel::importCodex,
        )
    }
}

/** Independent Settings overlay; exact tokens protect replacement dialogs from late callbacks. */
@Composable
public fun BoxScope.McpSettingsDialogs(viewModel: McpSettingsViewModel) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    when (val dialog = state.dialog) {
        McpSettingsDialog.Hidden -> Unit
        is McpSettingsDialog.Details -> McpServerDetailsDialog(
            server = dialog.server,
            onDismiss = { viewModel.dismiss(dialog.token) },
            onEdit = { viewModel.edit(dialog.token) },
            onDelete = { viewModel.requestDelete(dialog.token) },
            onSetEnabled = { viewModel.setEnabled(dialog.token, !dialog.server.enabled) },
            onLogin = { viewModel.login(dialog.token) },
            onCancelLogin = { viewModel.cancelLogin(dialog.token) },
            onLogout = { viewModel.logout(dialog.token) },
            onReconnect = { viewModel.reconnect(dialog.token) },
        )
        is McpSettingsDialog.Editing -> McpServerEditorDialog(
            editor = dialog,
            onDraftChange = { viewModel.updateDraft(dialog.token, it) },
            onDismiss = { viewModel.dismiss(dialog.token) },
            onSave = { viewModel.save(dialog.token) },
        )
        is McpSettingsDialog.Deleting -> McpDeleteConfirmationDialog(
            server = dialog.server,
            onDismiss = { viewModel.dismiss(dialog.token) },
            onConfirm = { viewModel.confirmDelete(dialog.token) },
            error = dialog.error,
        )
        is McpSettingsDialog.ImportLoading -> McpImportDialog(
            preview = null, decisions = emptyMap(),
            onToggle = {}, onSelectAll = {}, onClear = {}, onFilter = {}, onApply = {},
            onDismiss = { viewModel.dismiss(dialog.token) },
        )
        is McpSettingsDialog.ImportFailed -> McpImportDialog(
            preview = null, decisions = emptyMap(),
            onToggle = {}, onSelectAll = {}, onClear = {}, onFilter = {}, onApply = {},
            onDismiss = { viewModel.dismiss(dialog.token) },
            failed = true, onRetry = { viewModel.retryImport(dialog.token) },
        )
        is McpSettingsDialog.ImportPreview -> McpImportDialog(
            preview = dialog.preview, decisions = dialog.decisions,
            onToggle = { viewModel.toggleImport(dialog.token, it) },
            onSelectAll = { viewModel.selectAllImports(dialog.token) },
            onClear = { viewModel.clearImports(dialog.token) },
            onFilter = { viewModel.filterImport(dialog.token, it) },
            onApply = { viewModel.applyImport(dialog.token) },
            onDismiss = { viewModel.dismiss(dialog.token) },
            error = dialog.error,
        )
    }
}

/**
 * Exactly one Settings-lifetime consumer. [openUrl] returns true iff platform opening succeeded.
 * False/ordinary exception invokes this effect's exact cancellation callback, never name lookup.
 * Composition cancellation cancels that in-flight effect and rethrows; other login lifecycles
 * remain VM-owned until Settings close. URL data is not logged or retained in renderer state.
 */
@Composable
public fun McpSettingsEffects(
    viewModel: McpSettingsViewModel,
    openUrl: suspend (String) -> Boolean,
) {
    val currentOpen by rememberUpdatedState(openUrl)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is McpSettingsEffect.OpenAuthorizationUrl -> {
                    try {
                        if (!currentOpen(effect.url)) effect.cancel()
                    } catch (cancelled: CancellationException) {
                        effect.cancel()
                        throw cancelled
                    } catch (_: Throwable) {
                        effect.cancel()
                    }
                }
            }
        }
    }
}
