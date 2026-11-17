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
            renderer.TextField(editor.token, "Server name", draft.name, autoFocus = true) { value ->
                onDraftChange { it.copy(name = value) }
            }
            renderer.Choice(
                McpChoiceField.Transport.name, "Transport",
                McpTransportKind.entries.map { UiChoiceOption(it.name, if (it == McpTransportKind.StreamableHttp) "HTTP" else "stdio") },
                transport.name,
                onSelect = { selected -> McpTransportKind.entries.firstOrNull { it.name == selected }?.let { kind ->
                    onDraftChange { it.copy(transport = kind) }
                } }, enabled = true, supportingText = null,
            )
            renderer.TextField(
                editor.token,
                if (transport == McpTransportKind.StreamableHttp) "URL" else "Command",
                if (transport == McpTransportKind.StreamableHttp) draft.httpUrl else draft.command,
                autoFocus = false,
            ) { value ->
                onDraftChange { current ->
                    if (transport == McpTransportKind.StreamableHttp) current.copy(httpUrl = value) else current.copy(command = value)
                }
            }
            if (transport == McpTransportKind.Stdio) {
                renderer.TextField(editor.token, "Arguments (space separated)", draft.arguments, autoFocus = false) { value ->
                    onDraftChange { it.copy(arguments = value) }
                }
                renderer.TextField(editor.token, "Working directory", draft.workingDirectory, autoFocus = false) { value ->
                    onDraftChange { it.copy(workingDirectory = value) }
                }
                renderer.TextField(editor.token, "Environment (KEY=value; use <keep> for stored values)", draft.environment, autoFocus = false) { value ->
                    onDraftChange { it.copy(environment = value) }
                }
            } else {
                renderer.TextField(editor.token, "Headers (KEY=value; use <keep> for stored values)", draft.headers, autoFocus = false) { value ->
                    onDraftChange { it.copy(headers = value) }
                }
                renderer.Toggle("OAuth", draft.oauthEnabled, { enabled -> onDraftChange { it.copy(oauthEnabled = enabled) } },
                    enabled = true, supportingText = null)
                if (draft.oauthEnabled) {
                    renderer.TextField(editor.token, "OAuth client id (blank for dynamic registration)", draft.oauthClientId, autoFocus = false) { value ->
                        onDraftChange { it.copy(oauthClientId = value) }
                    }
                    renderer.TextField(editor.token, "OAuth client secret (blank for none; <keep> retains)", draft.oauthClientSecret, autoFocus = false) { value ->
                        onDraftChange { it.copy(oauthClientSecret = value) }
                    }
                    renderer.TextField(editor.token, "OAuth redirect URI", draft.oauthRedirect, autoFocus = false) { value ->
                        onDraftChange { it.copy(oauthRedirect = value) }
                    }
                    renderer.TextField(editor.token, "Authorization endpoint (blank for discovery)", draft.oauthAuthorizationEndpoint, autoFocus = false) { value ->
                        onDraftChange { it.copy(oauthAuthorizationEndpoint = value) }
                    }
                    renderer.TextField(editor.token, "Token endpoint (blank for discovery)", draft.oauthTokenEndpoint, autoFocus = false) { value ->
                        onDraftChange { it.copy(oauthTokenEndpoint = value) }
                    }
                    renderer.TextField(editor.token, "Resource (optional)", draft.oauthResource, autoFocus = false) { value ->
                        onDraftChange { it.copy(oauthResource = value) }
                    }
                    renderer.TextField(editor.token, "Scopes (comma separated)", draft.oauthScopes, autoFocus = false) { value ->
                        onDraftChange { it.copy(oauthScopes = value) }
                    }
                }
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
public actual fun McpDetailsContent(
    server: McpServerSettingsState, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onSetEnabled: () -> Unit, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit, onReconnect: () -> Unit,
    loginAvailable: Boolean,
    manualAuthorization: McpManualAuthorization?,
    frame: McpDialogFrameSlot,
) {
    frame(server.serverName, onDismiss) {
        Detail("Transport", server.transport.settingsLabel())
        Detail("Status", server.status.settingsLabel())
        Detail("Authentication", server.authentication.settingsLabel())
        manualAuthorization?.takeIf { it.isActive() }?.let {
            UiText("Open this address in your browser:", UiTextRole.Supporting)
            UiProse(it.url, UiTextRole.Supporting)
            UiAction("Cancel manual authorization", onClick = it.cancel)
        }
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
        UiActions {
            UiAction(if (server.enabled) "Disable" else "Enable", onClick = onSetEnabled)
            UiAction("Edit", onClick = onEdit)
            UiAction("Delete", onClick = onDelete, role = UiActionRole.Destructive)
        }
        UiActions {
            UiAction("Close", onClick = onDismiss)
            when (server.authentication) {
                McpAuthenticationState.LoginRequired, McpAuthenticationState.ReauthorizationRequired, is McpAuthenticationState.Failed ->
                    UiAction("Log in", onClick = onLogin, enabled = loginAvailable)
                McpAuthenticationState.Authorized, McpAuthenticationState.Refreshing -> UiAction("Log out", onClick = onLogout)
                McpAuthenticationState.Authorizing -> UiAction("Cancel login", onClick = onCancelLogin)
                McpAuthenticationState.NotConfigured -> Unit
            }
            if (server.status is McpServerSettingsStatus.Failed) UiAction("Reconnect", onClick = onReconnect)
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
        UiActions {
            UiAction("Cancel", onClick = onDismiss, autoFocus = true)
            UiAction("Delete", onClick = onConfirm, role = UiActionRole.Destructive)
        }
    }
}

@Composable
public actual fun McpImportContent(
    preview: McpImportPreview?,
    decisions: Map<String, McpImportDecision>,
    renderer: SettingsRenderer,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onFilter: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    error: String?,
    failed: Boolean,
    onRetry: () -> Unit,
    frame: McpImportFrameSlot,
) {
    val selectedCount = decisions.values.count { it != McpImportDecision.Skip }
    frame("Import MCP servers from Codex", onDismiss, {
        if (preview == null) {
            if (failed) {
                UiText("The Codex MCP settings could not be loaded.", UiTextRole.Error)
                UiAction("Retry", onClick = onRetry)
            } else UiText("Loading Codex MCP servers…")
        } else {
            renderer.TextField(preview.id, "Filter", preview.filter, autoFocus = false, onChange = onFilter)
            UiText("All supported servers are selected. Select a server to toggle it.", UiTextRole.Supporting)
            UiActions {
                UiAction("Select all", onClick = onSelectAll, enabled = decisions.isNotEmpty())
                UiAction("Clear", onClick = onClear, enabled = selectedCount > 0)
            }
            if (preview.items.isEmpty()) UiText("No Codex MCP servers found.")
            preview.items.forEach { item ->
                val decision = decisions[item.serverName] ?: McpImportDecision.Skip
                val marker = when {
                    !item.selectable -> "–"
                    decision == McpImportDecision.Skip -> " "
                    else -> "✓"
                }
                key(item.serverName) {
                    renderer.Entry("$marker ${item.serverName} ${item.kind.importLabel()}", enabled = item.selectable) {
                        onToggle(item.serverName)
                    }
                    item.detail?.let { UiText("  $it", UiTextRole.Supporting) }
                }
            }
        }
    }, {
        error?.let { UiText(it, UiTextRole.Error) }
        UiActions {
            UiAction("Cancel", onClick = onDismiss)
            UiAction("Import selected ($selectedCount)", onClick = onApply, enabled = preview != null && selectedCount > 0, role = UiActionRole.Primary)
        }
    })
}

private fun McpImportItemKind.importLabel(): String = when (this) {
    McpImportItemKind.New -> "New"
    McpImportItemKind.Conflict -> "Replace existing"
    McpImportItemKind.Unsupported -> "Unsupported"
}
