package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.app.mcpsettings.McpEditorDraft
import io.github.stream29.kodex.app.mcpsettings.McpSettingsDialog
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpTransportKind

/** Add/edit form whose persistent state never receives stored secret values. */
@Composable
internal fun BoxScope.McpServerEditorDialog(
    editor: McpSettingsDialog.Editing,
    onDraftChange: ((McpEditorDraft) -> McpEditorDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val draft = editor.draft
    val transport = draft.transport
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, McpEditorMaximumWidth)
    val transportDropdown = rememberTuiDropdownState()

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = if (editor.originalName == null) "Add MCP server" else "Edit MCP server",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            McpInputField("Server name", editor.token, draft.name, width, autoFocus = true) { name ->
                onDraftChange { it.copy(name = name) }
            }
            SettingsDropdownField(
                label = "Transport",
                selectedLabel = transport.editorLabel(),
                dropdownState = transportDropdown,
            )
            McpInputField(
                if (transport == McpTransportKind.StreamableHttp) "URL" else "Command",
                editor.token,
                if (transport == McpTransportKind.StreamableHttp) draft.httpUrl else draft.command,
                width,
            ) { value ->
                onDraftChange { current ->
                    if (transport == McpTransportKind.StreamableHttp) current.copy(httpUrl = value)
                    else current.copy(command = value)
                }
            }
            if (transport == McpTransportKind.Stdio) {
                McpInputField("Arguments (space separated)", editor.token, draft.arguments, width) { value ->
                    onDraftChange { it.copy(arguments = value) }
                }
                McpInputField("Working directory", editor.token, draft.workingDirectory, width) { value ->
                    onDraftChange { it.copy(workingDirectory = value) }
                }
                McpInputField(
                    "Environment (KEY=value; use <keep> for stored values)",
                    editor.token, draft.environment,
                    width,
                ) { value -> onDraftChange { it.copy(environment = value) } }
            } else {
                McpInputField(
                    "Headers (KEY=value; use <keep> for stored values)",
                    editor.token, draft.headers,
                    width,
                ) { value -> onDraftChange { it.copy(headers = value) } }
                SettingsCheckboxItem(
                    label = "OAuth",
                    checked = draft.oauthEnabled,
                    onCheckedChange = { enabled -> onDraftChange { it.copy(oauthEnabled = enabled) } },
                )
                if (draft.oauthEnabled) {
                    McpInputField(
                        "OAuth client id (blank for dynamic registration)",
                        editor.token, draft.oauthClientId,
                        width,
                    ) { value -> onDraftChange { it.copy(oauthClientId = value) } }
                    McpInputField(
                        "OAuth client secret (blank for none; <keep> retains)",
                        editor.token, draft.oauthClientSecret,
                        width,
                    ) { value -> onDraftChange { it.copy(oauthClientSecret = value) } }
                    McpInputField("OAuth redirect URI", editor.token, draft.oauthRedirect, width) { value ->
                        onDraftChange { it.copy(oauthRedirect = value) }
                    }
                    McpInputField(
                        "Authorization endpoint (blank for discovery)",
                        editor.token, draft.oauthAuthorizationEndpoint,
                        width,
                    ) { value -> onDraftChange { it.copy(oauthAuthorizationEndpoint = value) } }
                    McpInputField(
                        "Token endpoint (blank for discovery)",
                        editor.token, draft.oauthTokenEndpoint,
                        width,
                    ) { value -> onDraftChange { it.copy(oauthTokenEndpoint = value) } }
                    McpInputField("Resource (optional)", editor.token, draft.oauthResource, width) { value ->
                        onDraftChange { it.copy(oauthResource = value) }
                    }
                    McpInputField("Scopes (comma separated)", editor.token, draft.oauthScopes, width) { value ->
                        onDraftChange { it.copy(oauthScopes = value) }
                    }
                }
            }
            editor.error?.let { message ->
                SettingsErrorText(message)
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(label = "Cancel", onClick = onDismiss)
                SettingsPrimaryButton(label = "Save", onClick = onSave)
            }
        }
    }
    TuiDropdownMenu(
        dropdownState = transportDropdown,
        options = McpTransportKind.entries.toList(),
        selected = transport,
        optionLabel = McpTransportKind::editorLabel,
        backgroundColor = PopupMenuBackground,
        onSelect = { selected -> onDraftChange { it.copy(transport = selected) } },
    )
}

/** Sanitized server details and commands kept out of the Global Settings main surface. */
@Composable
internal fun BoxScope.McpServerDetailsDialog(
    server: McpServerSettingsState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: () -> Unit,
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    onReconnect: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, McpDetailsMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = server.serverName,
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            McpDetailLine("Transport", server.transport.settingsLabel())
            McpDetailLine("Status", server.status.settingsLabel())
            McpDetailLine("Authentication", server.authentication.settingsLabel())
            server.streamableHttpUrl?.let { url -> McpDetailLine("URL", url) }
            server.stdioCommand?.let { command -> McpDetailLine("Command", command) }
            if (server.stdioArguments.isNotEmpty()) {
                McpDetailLine("Arguments", server.stdioArguments.joinToString(" "))
            }
            server.stdioWorkingDirectory?.let { directory ->
                McpDetailLine("Working directory", directory.toString())
            }
            if (server.headerNames.isNotEmpty()) {
                McpDetailLine(
                    "Headers",
                    "${server.headerNames.joinToString()} (values hidden)",
                )
            }
            if (server.environmentNames.isNotEmpty()) {
                McpDetailLine(
                    "Environment",
                    "${server.environmentNames.joinToString()} (values hidden)",
                )
            }
            server.oauth?.let { oauth ->
                McpDetailLine(
                    "OAuth client",
                    oauth.clientId ?: "Dynamic registration pending",
                )
                McpDetailLine(
                    "OAuth client secret",
                    if (oauth.hasClientSecret) "Configured" else "None",
                )
                oauth.resource?.let { resource -> McpDetailLine("OAuth resource", resource) }
                if (oauth.scopes.isNotEmpty()) {
                    McpDetailLine("OAuth scopes", oauth.scopes.joinToString())
                }
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(
                    label = if (server.enabled) "Disable" else "Enable",
                    onClick = onSetEnabled,
                )
                SettingsActionButton(label = "Edit", onClick = onEdit)
                SettingsDangerButton(
                    label = "Delete",
                    onClick = onDelete,
                )
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(label = "Close", onClick = onDismiss)
                when (server.authentication) {
                    McpAuthenticationState.LoginRequired,
                    McpAuthenticationState.ReauthorizationRequired,
                    is McpAuthenticationState.Failed,
                        -> {
                        SettingsActionButton(label = "Log in", onClick = onLogin)
                    }

                    McpAuthenticationState.Authorized,
                    McpAuthenticationState.Refreshing,
                        -> {
                        SettingsActionButton(label = "Log out", onClick = onLogout)
                    }

                    McpAuthenticationState.Authorizing -> {
                        SettingsActionButton(
                            label = "Cancel login",
                            onClick = onCancelLogin,
                        )
                    }

                    McpAuthenticationState.NotConfigured -> Unit
                }
                if (server.status is McpServerSettingsStatus.Failed) {
                    SettingsActionButton(
                        label = "Reconnect",
                        onClick = onReconnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun McpDetailLine(label: String, value: String) {
    Text(
        value = "$label: $value",
        color = SettingsForeground,
        textStyle = TextStyle.Dim,
    )
}

@Composable
internal fun BoxScope.McpDeleteConfirmationDialog(
    server: McpServerSettingsState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    error: String? = null,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, McpDeleteMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                "Delete MCP server",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text("Delete '${server.serverName}'?", color = SettingsForeground)
            error?.let { SettingsErrorText(it) }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(
                    label = "Cancel",
                    autoFocus = true,
                    onClick = onDismiss,
                )
                SettingsDangerButton(
                    label = "Delete",
                    prominent = true,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** Direct selection flow; opening the dialog starts preview loading before it is rendered. */
@Composable
internal fun BoxScope.McpImportDialog(
    preview: McpImportPreview?,
    decisions: Map<String, McpImportDecision>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onFilter: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
) {
    val terminalSize = LocalTerminalState.current.size
    val width = (terminalSize.columns - 4)
        .coerceIn(1, McpImportMaximumWidth)
    val height = (terminalSize.rows - 4)
        .coerceIn(1, McpImportMaximumHeight)
    val scrollState = remember(preview?.id) { ScrollState() }
    val selectedCount = decisions.values.count { decision ->
        decision != McpImportDecision.Skip
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).height(height).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                "Import MCP servers from Codex",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
            ) {
                if (preview == null) {
                    if (failed) {
                        SettingsErrorText("The Codex MCP settings could not be loaded.")
                        SettingsActionButton(label = "Retry", onClick = onRetry)
                    } else {
                        Text("Loading Codex MCP servers…", color = SettingsForeground)
                    }
                } else {
                    McpInputField("Filter", preview.id, preview.filter, width, onChange = onFilter)
                    Text(
                        "All supported servers are selected. Select a server to toggle it.",
                        color = SettingsForeground,
                        textStyle = TextStyle.Dim,
                    )
                    Row {
                        SettingsActionButton(
                            label = "Select all",
                            // Decisions include filtered-out candidates; Select all is not
                            // accidentally disabled by a filter matching no visible supported row.
                            enabled = decisions.isNotEmpty(),
                            onClick = onSelectAll,
                        )
                        Text(" ")
                        SettingsActionButton(
                            label = "Clear",
                            enabled = selectedCount > 0,
                            onClick = onClear,
                        )
                    }
                    if (preview.items.isEmpty()) {
                        Text("No Codex MCP servers found.", color = SettingsForeground)
                    }
                    preview.items.forEach { item ->
                        val decision = decisions[item.serverName] ?: McpImportDecision.Skip
                        val marker = when {
                            !item.selectable -> "–"
                            decision == McpImportDecision.Skip -> " "
                            else -> "✓"
                        }
                        SettingsContentButton(
                            label = "$marker ${item.serverName} ${item.kind.importLabel()}",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = item.selectable,
                            onClick = { onToggle(item.serverName) },
                        )
                        item.detail?.let { detail ->
                            Text(
                                value = "  $detail",
                                color = SettingsForeground,
                                textStyle = TextStyle.Dim,
                            )
                        }
                    }
                }
            }
            error?.let { SettingsErrorText(it) }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                SettingsActionButton(label = "Cancel", onClick = onDismiss)
                SettingsPrimaryButton(
                    label = "Import selected ($selectedCount)",
                    enabled = preview != null && selectedCount > 0,
                    onClick = onApply,
                )
            }
        }
    }
}

@Composable
private fun McpInputField(
    label: String,
    token: Any,
    text: String,
    width: Int,
    autoFocus: Boolean = false,
    onChange: (String) -> Unit,
) {
    val state = rememberInput(token, text)
    Text(label, color = SettingsForeground)
    TextInput(
        state = state,
        layout = TextInputLayout.create(state.value, width),
        modifier = Modifier.fillMaxWidth(),
        autoFocus = autoFocus,
        onValueChanged = { onChange(it.text) },
    )
}

@Composable
private fun rememberInput(token: Any, text: String): TextInputState {
    val state = remember(token) {
        TextInputState(
            TextInputValue(
                text = text,
                cursorOffset = text.length,
            ),
        )
    }
    LaunchedEffect(text) {
        if (state.value.text != text) {
            state.reset(TextInputValue(text, state.value.cursorOffset.coerceAtMost(text.length)))
        }
    }
    return state
}

private fun McpTransportKind.editorLabel(): String =
    when (this) {
        McpTransportKind.StreamableHttp -> "HTTP"
        McpTransportKind.Stdio -> "stdio"
    }

private fun McpImportItemKind.importLabel(): String =
    when (this) {
        McpImportItemKind.New -> "New"
        McpImportItemKind.Conflict -> "Replace existing"
        McpImportItemKind.Unsupported -> "Unsupported"
    }

private const val McpEditorMaximumWidth: Int = 84
private const val McpDetailsMaximumWidth: Int = 84
private const val McpDeleteMaximumWidth: Int = 56
private const val McpImportMaximumWidth: Int = 76
private const val McpImportMaximumHeight: Int = 28
