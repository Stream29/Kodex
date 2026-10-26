package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.mcp.contract.*

@Composable
public actual fun McpEditorContent(
    editor: McpSettingsDialog.Editing, renderer: SettingsRenderer,
    onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
    frame: McpDialogFrameSlot,
) {
    key(editor.token) {
        val draft = editor.draft
        val transport = draft.transport
        frame(if (editor.originalName == null) "Add MCP server" else "Edit MCP server", onDismiss) {
            McpInput("Server name", editor.token, draft.name, autoFocus = true) { value -> onDraftChange { it.copy(name = value) } }
            renderer.Choice(
                McpChoiceField.Transport.name, "Transport",
                McpTransportKind.entries.map { UiChoiceOption(it.name, if (it == McpTransportKind.StreamableHttp) "HTTP" else "stdio") },
                transport.name,
                onSelect = { selected -> McpTransportKind.entries.firstOrNull { it.name == selected }?.let { kind ->
                    onDraftChange { it.copy(transport = kind) }
                } },
            )
            McpInput(
                if (transport == McpTransportKind.StreamableHttp) "URL" else "Command", editor.token,
                if (transport == McpTransportKind.StreamableHttp) draft.httpUrl else draft.command,
            ) { value ->
                onDraftChange { current ->
                    if (transport == McpTransportKind.StreamableHttp) current.copy(httpUrl = value) else current.copy(command = value)
                }
            }
            if (transport == McpTransportKind.Stdio) {
                McpInput("Arguments (space separated)", editor.token, draft.arguments) { value -> onDraftChange { it.copy(arguments = value) } }
                McpInput("Working directory", editor.token, draft.workingDirectory) { value -> onDraftChange { it.copy(workingDirectory = value) } }
                McpInput("Environment (KEY=value; use <keep> for stored values)", editor.token, draft.environment) { value ->
                    onDraftChange { it.copy(environment = value) }
                }
            } else {
                McpInput("Headers (KEY=value; use <keep> for stored values)", editor.token, draft.headers) { value -> onDraftChange { it.copy(headers = value) } }
                renderer.Toggle("OAuth", draft.oauthEnabled, { enabled -> onDraftChange { it.copy(oauthEnabled = enabled) } })
                if (draft.oauthEnabled) {
                    McpInput("OAuth client id (blank for dynamic registration)", editor.token, draft.oauthClientId) { value -> onDraftChange { it.copy(oauthClientId = value) } }
                    McpInput("OAuth client secret (blank for none; <keep> retains)", editor.token, draft.oauthClientSecret) { value -> onDraftChange { it.copy(oauthClientSecret = value) } }
                    McpInput("OAuth redirect URI", editor.token, draft.oauthRedirect) { value -> onDraftChange { it.copy(oauthRedirect = value) } }
                    McpInput("Authorization endpoint (blank for discovery)", editor.token, draft.oauthAuthorizationEndpoint) { value -> onDraftChange { it.copy(oauthAuthorizationEndpoint = value) } }
                    McpInput("Token endpoint (blank for discovery)", editor.token, draft.oauthTokenEndpoint) { value -> onDraftChange { it.copy(oauthTokenEndpoint = value) } }
                    McpInput("Resource (optional)", editor.token, draft.oauthResource) { value -> onDraftChange { it.copy(oauthResource = value) } }
                    McpInput("Scopes (comma separated)", editor.token, draft.oauthScopes) { value -> onDraftChange { it.copy(oauthScopes = value) } }
                }
            }
            editor.error?.let { UiText(it, UiTextRole.Error) }
            McpActions {
                McpAction("Cancel", onClick = onDismiss)
                McpAction("Save", McpActionRole.Primary, onClick = onSave)
            }
        }
    }
}

@Composable
public actual fun McpDetailsContent(
    server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
    loginAvailable: Boolean,
    frame: McpDialogFrameSlot,
) {
    frame(server.serverName, onDismiss) {
        Detail("Transport", server.transport.settingsLabel())
        Detail("Status", server.status.settingsLabel())
        Detail("Authentication", server.authentication.settingsLabel())
        server.streamableHttpUrl?.let { Detail("URL", it) }
        server.stdioCommand?.let { Detail("Command", it) }
        if (server.stdioArguments.isNotEmpty()) Detail("Arguments", server.stdioArguments.joinToString(" "))
        server.stdioWorkingDirectory?.let { Detail("Working directory", it.toString()) }
        if (server.headerNames.isNotEmpty()) Detail("Headers", "${server.headerNames.joinToString()} (values hidden)")
        if (server.environmentNames.isNotEmpty()) Detail("Environment", "${server.environmentNames.joinToString()} (values hidden)")
        server.oauth?.let { oauth ->
            Detail("OAuth client", oauth.clientId ?: "Dynamic registration pending")
            Detail("OAuth client secret", if (oauth.hasClientSecret) "Configured" else "None")
            oauth.resource?.let { Detail("OAuth resource", it) }
            if (oauth.scopes.isNotEmpty()) Detail("OAuth scopes", oauth.scopes.joinToString())
        }
        McpActions {
            McpAction(if (server.enabled) "Disable" else "Enable", onClick = onSetEnabled)
            McpAction("Edit", onClick = onEdit)
            McpAction("Delete", McpActionRole.Danger, onClick = onDelete)
        }
        McpActions {
            McpAction("Close", onClick = onDismiss)
            when (server.authentication) {
                McpAuthenticationState.LoginRequired, McpAuthenticationState.ReauthorizationRequired, is McpAuthenticationState.Failed ->
                    McpAction("Log in", enabled = loginAvailable, onClick = onLogin)
                McpAuthenticationState.Authorized, McpAuthenticationState.Refreshing -> McpAction("Log out", onClick = onLogout)
                McpAuthenticationState.Authorizing -> McpAction("Cancel login", onClick = onCancelLogin)
                McpAuthenticationState.NotConfigured -> Unit
            }
            if (server.status is McpServerSettingsStatus.Failed) McpAction("Reconnect", onClick = onReconnect)
        }
        if (!loginAvailable) UiText("Provider sign-in is unavailable in this host. Use the Native client.", UiTextRole.Supporting)
    }
}

@Composable private fun Detail(label: String, value: String) { UiText("$label: $value", UiTextRole.Supporting) }

@Composable
public actual fun McpDeleteContent(
    server: McpServerSettingsState, error: String?, onDismiss: () -> Unit, onConfirm: () -> Unit, frame: McpDialogFrameSlot,
) {
    frame("Delete MCP server", onDismiss) {
        UiText("Delete '${server.serverName}'?")
        error?.let { UiText(it, UiTextRole.Error) }
        McpActions {
            McpAction("Cancel", autoFocus = true, onClick = onDismiss)
            McpAction("Delete", McpActionRole.ProminentDanger, onClick = onConfirm)
        }
    }
}

@Composable
public actual fun McpImportContent(
    preview: McpImportPreview?, decisions: Map<String, McpImportDecision>,
    onToggle: (String) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit, onFilter: (String) -> Unit,
    onApply: () -> Unit, onDismiss: () -> Unit, error: String?, failed: Boolean, onRetry: () -> Unit,
    frame: McpImportFrameSlot,
) {
    val selectedCount = decisions.values.count { it != McpImportDecision.Skip }
    frame("Import MCP servers from Codex", onDismiss, {
        if (preview == null) {
            if (failed) {
                UiText("The Codex MCP settings could not be loaded.", UiTextRole.Error)
                McpAction("Retry", onClick = onRetry)
            } else UiText("Loading Codex MCP servers…")
        } else {
            McpInput("Filter", preview.id, preview.filter, onChange = onFilter)
            UiText("All supported servers are selected. Select a server to toggle it.", UiTextRole.Supporting)
            McpInlineActions(
                first = { McpAction("Select all", enabled = decisions.isNotEmpty(), onClick = onSelectAll) },
                second = { McpAction("Clear", enabled = selectedCount > 0, onClick = onClear) },
            )
            if (preview.items.isEmpty()) UiText("No Codex MCP servers found.")
            preview.items.forEach { item ->
                val decision = decisions[item.serverName] ?: McpImportDecision.Skip
                val marker = when {
                    !item.selectable -> "–"
                    decision == McpImportDecision.Skip -> " "
                    else -> "✓"
                }
                key(item.serverName) {
                    McpImportEntry("$marker ${item.serverName} ${item.kind.importLabel()}", item.selectable) { onToggle(item.serverName) }
                    item.detail?.let { UiText("  $it", UiTextRole.Supporting) }
                }
            }
        }
    }, {
        error?.let { UiText(it, UiTextRole.Error) }
        McpActions {
            McpAction("Cancel", onClick = onDismiss)
            McpAction("Import selected ($selectedCount)", McpActionRole.Primary, enabled = preview != null && selectedCount > 0, onClick = onApply)
        }
    })
}

private fun McpImportItemKind.importLabel(): String = when (this) {
    McpImportItemKind.New -> "New"
    McpImportItemKind.Conflict -> "Replace existing"
    McpImportItemKind.Unsupported -> "Unsupported"
}
