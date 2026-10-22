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

@Composable
public actual fun BoxScope.McpSettingsComponent(viewModel: McpSettingsViewModel) {
    McpSettingsPanel(viewModel)
    McpSettingsDialogs(viewModel)
}

@Composable
public actual fun McpSettingsPanel(viewModel: McpSettingsViewModel, showOperationFailure: Boolean) {
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

@Composable
public actual fun BoxScope.McpSettingsDialogs(viewModel: McpSettingsViewModel) {
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

@Composable
public actual fun McpSettingsEffects(
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
