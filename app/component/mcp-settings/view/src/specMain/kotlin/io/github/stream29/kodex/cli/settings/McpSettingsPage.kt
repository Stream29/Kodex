package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.mcp.contract.*

public typealias McpDialogFrameSlot = @Composable (title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit
public typealias McpImportFrameSlot = @Composable (
    title: String, onDismiss: () -> Unit, body: @Composable () -> Unit, footer: @Composable () -> Unit,
) -> Unit
public enum class McpChoiceField { Transport }

/** Mounts shared forms only; modal geometry, transport-menu anchors and scrolling stay in the renderer. */
public interface McpDialogRenderer {
    @Composable
    public fun Editor(
        editor: McpSettingsDialog.Editing, onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit,
        onDismiss: () -> Unit, onSave: () -> Unit,
    )
    @Composable
    public fun Details(
        server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
        onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
    )
    @Composable
    public fun Deleting(server: McpServerSettingsState, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit)
    @Composable
    public fun Import(
        preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
        onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
        onApply: () -> Unit, onDismiss: () -> Unit, error: String?, failed: Boolean, onRetry: () -> Unit,
    )
}

@Composable
public expect fun McpSettingsPage(viewModel: McpSettingsViewModel, showOperationFailure: Boolean = true): Unit
@Composable
public expect fun McpSettingsDialogHost(viewModel: McpSettingsViewModel, renderer: McpDialogRenderer): Unit
@Composable
public expect fun McpSettingsContent(
    servers: List<McpServerSettingsState>, onAdd: () -> Unit, onOpenDetails: (McpServerSettingsState) -> Unit, onImport: () -> Unit,
): Unit

/**
 * One consumer for the entire exact Settings owner, retained across temporary Login and return.
 * Failure/cancellation cancels only the captured effect; URLs are transient and launcher acceptance
 * is not authentication completion. The host injects opening, never a backend-machine launcher.
 */
@Composable
public expect fun McpSettingsEffects(viewModel: McpSettingsViewModel, openUrl: suspend (String) -> Boolean): Unit

@Composable
public expect fun McpEditorContent(
    editor: McpSettingsDialog.Editing, renderer: SettingsRenderer,
    onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
    frame: McpDialogFrameSlot,
): Unit
@Composable
public expect fun McpDetailsContent(
    server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
    loginAvailable: Boolean = true,
    frame: McpDialogFrameSlot,
): Unit
@Composable
public expect fun McpDeleteContent(
    server: McpServerSettingsState, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit, frame: McpDialogFrameSlot,
): Unit
@Composable
public expect fun McpImportContent(
    preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
    onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
    onApply: () -> Unit, onDismiss: () -> Unit, error: String? = null, failed: Boolean = false, onRetry: () -> Unit = {},
    frame: McpImportFrameSlot,
): Unit

public enum class McpActionRole { Normal, Primary, Danger, ProminentDanger }
@Composable internal expect fun McpListFrame(content: @Composable () -> Unit)
@Composable internal expect fun McpListHeader(title: String, add: String, importLabel: String, onAdd: () -> Unit, onImport: () -> Unit)
@Composable internal expect fun McpListEntry(label: String, onClick: () -> Unit)
@Composable internal expect fun McpInput(label: String, token: Any, value: String, autoFocus: Boolean = false, onChange: (String) -> Unit)
@Composable internal expect fun McpActions(content: @Composable () -> Unit)
@Composable internal expect fun McpInlineActions(first: @Composable () -> Unit, second: @Composable () -> Unit)
@Composable internal expect fun McpImportEntry(label: String, enabled: Boolean, onClick: () -> Unit)
@Composable internal expect fun McpAction(
    label: String, role: McpActionRole = McpActionRole.Normal, enabled: Boolean = true,
    autoFocus: Boolean = false, onClick: () -> Unit,
)
