package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.EditorDialog as Dialog
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.contract.*

@Composable public actual fun rememberComposeMcpDialogRenderer(): McpDialogRenderer = remember { ComposeMcpDialogs() }

private class ComposeMcpDialogs : McpDialogRenderer {
    @Composable override fun Editor(
        editor: McpSettingsDialog.Editing, onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit,
        onDismiss: () -> Unit, onSave: () -> Unit,
    ) {
        McpEditorContent(editor, rememberComposeSettingsRenderer(), onDraftChange, onDismiss, onSave) { title, dismiss, content ->
            McpDialogFrame(title, dismiss, content)
        }
    }
    @Composable override fun Details(
        server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
        onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
    ) {
        McpDetailsContent(server, onDismiss, onEdit, onDelete, onSetEnabled, onLogin, onCancelLogin, onLogout, onReconnect,
            loginAvailable = LocalGraphicalHostCapabilities.current.providerLogin,
        ) { title, dismiss, content ->
            McpDialogFrame(title, dismiss, content)
        }
    }
    @Composable override fun Deleting(server: McpServerSettingsState, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        McpDeleteContent(server, error, onDismiss, onConfirm) { title, dismiss, content -> McpDialogFrame(title, dismiss, content) }
    }
    @Composable override fun Import(
        preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
        onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
        onApply: () -> Unit, onDismiss: () -> Unit, error: String?, failed: Boolean, onRetry: () -> Unit,
    ) {
        McpImportContent(
            preview, decisions, onToggle, onSelectAll, onClear, onFilter, onApply, onDismiss, error, failed, onRetry,
        ) { title, dismiss, body, footer -> McpDialogFrame(title, dismiss, body, footer) }
    }
}

@Composable private fun McpDialogFrame(
    title: String, onDismiss: () -> Unit, body: @Composable () -> Unit, footer: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.widthIn(max = 760.dp).heightIn(max = 640.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                UiText(title, UiTextRole.Title)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { body() }
                footer?.invoke()
            }
        }
    }
}
