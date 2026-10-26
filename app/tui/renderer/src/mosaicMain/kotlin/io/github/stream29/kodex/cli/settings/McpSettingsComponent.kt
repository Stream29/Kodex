package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.mcp.contract.*

@Composable public actual fun BoxScope.McpSettingsComponent(viewModel: McpSettingsViewModel) {
    McpSettingsPanel(viewModel); McpSettingsDialogs(viewModel)
}
@Composable public actual fun McpSettingsPanel(viewModel: McpSettingsViewModel, showOperationFailure: Boolean) {
    McpSettingsPage(viewModel, showOperationFailure)
}
@Composable public actual fun BoxScope.McpSettingsDialogs(viewModel: McpSettingsViewModel) {
    McpSettingsDialogHost(viewModel, remember(this) { MosaicMcpDialogs(this) })
}

internal class MosaicMcpDialogs(private val box: BoxScope) : McpDialogRenderer {
    @Composable override fun Editor(
        editor: McpSettingsDialog.Editing, onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit,
        onDismiss: () -> Unit, onSave: () -> Unit,
    ) { with(box) { McpServerEditorDialog(editor, onDraftChange, onDismiss, onSave) } }
    @Composable override fun Details(
        server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
        onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
    ) { with(box) { McpServerDetailsDialog(server, onDismiss, onEdit, onDelete, onSetEnabled, onLogin, onCancelLogin, onLogout, onReconnect) } }
    @Composable override fun Deleting(server: McpServerSettingsState, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        with(box) { McpDeleteConfirmationDialog(server, onDismiss, onConfirm, error) }
    }
    @Composable override fun Import(
        preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
        onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
        onApply: () -> Unit, onDismiss: () -> Unit, error: String?, failed: Boolean, onRetry: () -> Unit,
    ) { with(box) { McpImportDialog(preview, decisions, onToggle, onSelectAll, onClear, onFilter, onApply, onDismiss, error, failed, onRetry) } }
}

@Composable internal fun BoxScope.McpServerEditorDialog(
    editor: McpSettingsDialog.Editing, onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit,
) {
    val transport = rememberTuiDropdownState()
    val renderer = remember(transport) { MosaicSettingsRenderer(mapOf(McpChoiceField.Transport.name to transport)) }
    McpEditorContent(editor, renderer, onDraftChange, onDismiss, onSave) { title, dismiss, content ->
        McpDialogFrame(title, 84, dismiss, content)
    }
    with(renderer) { Menus() }
}

@Composable internal fun BoxScope.McpServerDetailsDialog(
    server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
) {
    McpDetailsContent(server, onDismiss, onEdit, onDelete, onSetEnabled, onLogin, onCancelLogin, onLogout, onReconnect) { title, dismiss, content ->
        McpDialogFrame(title, 84, dismiss, content)
    }
}

@Composable internal fun BoxScope.McpDeleteConfirmationDialog(
    server: McpServerSettingsState, onDismiss: () -> Unit, onConfirm: () -> Unit, error: String? = null,
) {
    McpDeleteContent(server, error, onDismiss, onConfirm) { title, dismiss, content ->
        McpDialogFrame(title, 56, dismiss, content)
    }
}

@Composable internal fun BoxScope.McpImportDialog(
    preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
    onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
    onApply: () -> Unit, onDismiss: () -> Unit, error: String? = null, failed: Boolean = false, onRetry: () -> Unit = {},
) {
    val size = LocalTerminalState.current.size
    val width = (size.columns - 4).coerceIn(1, 76)
    val height = (size.rows - 4).coerceIn(1, 28)
    val scroll = remember(preview?.id) { ScrollState() }
    McpImportContent(
        preview, decisions, onToggle, onSelectAll, onClear, onFilter, onApply, onDismiss, error, failed, onRetry,
    ) { title, dismiss, body, footer ->
        TuiDialog(
            onDismissRequest = dismiss,
            modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
        ) {
            Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
                McpDialogHeader(title)
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) { body() }
                footer()
            }
        }
    }
}

@Composable private fun BoxScope.McpDialogFrame(title: String, maximumWidth: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val width = (LocalTerminalState.current.size.columns - 4).coerceIn(1, maximumWidth)
    TuiDialog(onDismissRequest = onDismiss, modifier = Modifier.width(width).background(SettingsDialogBackground)) {
        Column(Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            McpDialogHeader(title)
            content()
        }
    }
}

@Composable private fun McpDialogHeader(title: String) {
    Text(
        title, Modifier.fillMaxWidth().background(SettingsHeaderBackground),
        color = SettingsForeground, textStyle = TuiTheme.typography.headline,
    )
}
