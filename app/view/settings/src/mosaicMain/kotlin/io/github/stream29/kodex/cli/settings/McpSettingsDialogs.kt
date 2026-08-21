package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.mcp.contract.DefaultMcpOAuthRedirectUri
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpOAuthDraft
import io.github.stream29.kodex.mcp.contract.McpSecretDraft
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpStdioDraft
import io.github.stream29.kodex.mcp.contract.McpStreamableHttpDraft
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.io.files.Path

internal data class McpEditorRequest(
    val existing: McpServerSettingsState? = null,
)

/** Add/edit form whose persistent state never receives stored secret values. */
@Composable
internal fun BoxScope.McpServerEditorDialog(
    request: McpEditorRequest,
    onDismiss: () -> Unit,
    onSave: (McpServerDraft) -> Unit,
) {
    val existing = request.existing
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, McpEditorMaximumWidth)
    var transport by remember(request) {
        mutableStateOf(existing?.transport ?: McpTransportKind.StreamableHttp)
    }
    var oauthEnabled by remember(request) {
        mutableStateOf(existing?.oauth != null)
    }
    val transportDropdown = rememberTuiDropdownState()
    var error by remember(request) { mutableStateOf<String?>(null) }
    val name = rememberInput(existing?.serverName.orEmpty())
    val httpUrl = rememberInput(existing?.streamableHttpUrl.orEmpty())
    val stdioCommand = rememberInput(existing?.stdioCommand.orEmpty())
    val arguments = rememberInput(existing?.stdioArguments?.formatArguments().orEmpty())
    val headers = rememberInput(existing?.headerNames?.keepValuesText().orEmpty())
    val environment = rememberInput(existing?.environmentNames?.keepValuesText().orEmpty())
    val workingDirectory = rememberInput(existing?.stdioWorkingDirectory?.toString() ?: ".")
    val oauthClientId = rememberInput(existing?.oauth?.clientId.orEmpty())
    val oauthClientSecret = rememberInput(
        if (existing?.oauth?.hasClientSecret == true) KeepValueMarker else "",
    )
    val oauthRedirect = rememberInput(
        existing?.oauth?.redirectUri ?: DefaultMcpOAuthRedirectUri,
    )
    val oauthAuthorizationEndpoint = rememberInput(
        existing?.oauth?.authorizationEndpoint.orEmpty(),
    )
    val oauthTokenEndpoint = rememberInput(existing?.oauth?.tokenEndpoint.orEmpty())
    val oauthResource = rememberInput(existing?.oauth?.resource.orEmpty())
    val oauthScopes = rememberInput(existing?.oauth?.scopes?.joinToString(",").orEmpty())

    fun save() {
        runCatching {
            val serverName = name.value.text.trim()
            require(serverName.isNotEmpty()) { "Server name is required." }
            val endpointValue = when (transport) {
                McpTransportKind.StreamableHttp -> httpUrl.value.text.trim()
                McpTransportKind.Stdio -> stdioCommand.value.text.trim()
            }
            require(endpointValue.isNotEmpty()) { "A URL or command is required." }
            when (transport) {
                McpTransportKind.StreamableHttp -> McpServerDraft.StreamableHttp(
                    serverName = serverName,
                    enabled = existing?.enabled ?: true,
                    configuration = McpStreamableHttpDraft(
                        url = endpointValue,
                        headers = parseSecretEntries(headers.value.text),
                        oauth = if (oauthEnabled) {
                            val clientId = oauthClientId.value.text.trim().ifEmpty { null }
                            val redirectUri = oauthRedirect.value.text.trim()
                            require(redirectUri.isNotEmpty()) {
                                "OAuth redirect URI is required."
                            }
                            McpOAuthDraft(
                                clientId = clientId,
                                clientSecret = oauthClientSecret.value.text.toOptionalSecretDraft(),
                                redirectUri = redirectUri,
                                authorizationEndpoint =
                                    oauthAuthorizationEndpoint.value.text.trim().ifEmpty { null },
                                tokenEndpoint =
                                    oauthTokenEndpoint.value.text.trim().ifEmpty { null },
                                resource = oauthResource.value.text.trim().ifEmpty { null },
                                scopes = oauthScopes.value.text
                                    .split(',')
                                    .map(String::trim)
                                    .filter(String::isNotEmpty),
                            )
                        } else {
                            null
                        },
                    ),
                )

                McpTransportKind.Stdio -> McpServerDraft.Stdio(
                    serverName = serverName,
                    enabled = existing?.enabled ?: true,
                    configuration = McpStdioDraft(
                        command = endpointValue,
                        args = parseArguments(arguments.value.text),
                        environment = parseSecretEntries(environment.value.text),
                        workingDirectory = Path(
                            workingDirectory.value.text.trim().ifEmpty { "." },
                        ),
                    ),
                )
            }
        }.fold(
            onSuccess = onSave,
            onFailure = { failure ->
                error = failure.message ?: "The MCP server configuration is invalid."
            },
        )
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsDialogBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogBackground)) {
            Text(
                value = if (existing == null) "Add MCP server" else "Edit MCP server",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            McpInputField("Server name", name, width, autoFocus = true)
            SettingsDropdownField(
                label = "Transport",
                selectedLabel = transport.editorLabel(),
                dropdownState = transportDropdown,
            )
            McpInputField(
                if (transport == McpTransportKind.StreamableHttp) "URL" else "Command",
                if (transport == McpTransportKind.StreamableHttp) httpUrl else stdioCommand,
                width,
            )
            if (transport == McpTransportKind.Stdio) {
                McpInputField("Arguments (space separated)", arguments, width)
                McpInputField("Working directory", workingDirectory, width)
                McpInputField(
                    "Environment (KEY=value; use <keep> for stored values)",
                    environment,
                    width,
                )
            } else {
                McpInputField(
                    "Headers (KEY=value; use <keep> for stored values)",
                    headers,
                    width,
                )
                SettingsCheckboxItem(
                    label = "OAuth",
                    checked = oauthEnabled,
                    onCheckedChange = { enabled -> oauthEnabled = enabled },
                )
                if (oauthEnabled) {
                    McpInputField(
                        "OAuth client id (blank for dynamic registration)",
                        oauthClientId,
                        width,
                    )
                    McpInputField(
                        "OAuth client secret (blank for none; <keep> retains)",
                        oauthClientSecret,
                        width,
                    )
                    McpInputField("OAuth redirect URI", oauthRedirect, width)
                    McpInputField(
                        "Authorization endpoint (blank for discovery)",
                        oauthAuthorizationEndpoint,
                        width,
                    )
                    McpInputField(
                        "Token endpoint (blank for discovery)",
                        oauthTokenEndpoint,
                        width,
                    )
                    McpInputField("Resource (optional)", oauthResource, width)
                    McpInputField("Scopes (comma separated)", oauthScopes, width)
                }
            }
            error?.let { message ->
                SettingsErrorText(message)
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Cancel", color = SettingsActionForeground, onClick = onDismiss)
                TuiButton(label = "Save", color = SettingsActionForeground, onClick = ::save)
            }
        }
    }
    TuiDropdownMenu(
        dropdownState = transportDropdown,
        options = McpTransportKind.entries.toList(),
        selected = transport,
        optionLabel = McpTransportKind::editorLabel,
        backgroundColor = PopupMenuBackground,
        onSelect = { selected -> transport = selected },
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
                TuiButton(
                    label = if (server.enabled) "Disable" else "Enable",
                    color = SettingsActionForeground,
                    onClick = onSetEnabled,
                )
                TuiButton(label = "Edit", color = SettingsActionForeground, onClick = onEdit)
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Close", color = SettingsActionForeground, onClick = onDismiss)
                when (server.authentication) {
                    McpAuthenticationState.LoginRequired,
                    McpAuthenticationState.ReauthorizationRequired,
                    is McpAuthenticationState.Failed,
                        -> {
                        TuiButton(label = "Log in", color = SettingsActionForeground, onClick = onLogin)
                    }

                    McpAuthenticationState.Authorized,
                    McpAuthenticationState.Refreshing,
                        -> {
                        TuiButton(label = "Log out", color = SettingsActionForeground, onClick = onLogout)
                    }

                    McpAuthenticationState.Authorizing -> {
                        TuiButton(
                            label = "Cancel login",
                            color = SettingsActionForeground,
                            onClick = onCancelLogin,
                        )
                    }

                    McpAuthenticationState.NotConfigured -> Unit
                }
                if (server.status is McpServerSettingsStatus.Failed) {
                    TuiButton(
                        label = "Reconnect",
                        color = SettingsActionForeground,
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
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(
                    label = "Cancel",
                    color = SettingsActionForeground,
                    autoFocus = true,
                    onClick = onDismiss,
                )
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
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
    onApply: (Long, Map<String, McpImportDecision>) -> Unit,
    onDismiss: () -> Unit,
) {
    val terminalSize = LocalTerminalState.current.size
    val width = (terminalSize.columns - 4)
        .coerceIn(1, McpImportMaximumWidth)
    val height = (terminalSize.rows - 4)
        .coerceIn(1, McpImportMaximumHeight)
    val scrollState = remember(preview?.id) { ScrollState() }
    var decisions by remember(preview?.id) {
        mutableStateOf(preview?.defaultImportDecisions().orEmpty())
    }
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
                    Text("Loading Codex MCP servers…", color = SettingsForeground)
                } else {
                    Text(
                        "All supported servers are selected. Select a server to toggle it.",
                        color = SettingsForeground,
                        textStyle = TextStyle.Dim,
                    )
                    Row {
                        TuiButton(
                            label = "Select all",
                            color = SettingsActionForeground,
                            enabled = preview.items.any { item -> item.selectable },
                            onClick = { decisions = preview.defaultImportDecisions() },
                        )
                        Text(" ")
                        TuiButton(
                            label = "Clear",
                            color = SettingsActionForeground,
                            enabled = selectedCount > 0,
                            onClick = {
                                decisions = preview.items.associate { item ->
                                    item.serverName to McpImportDecision.Skip
                                }
                            },
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
                        TuiButton(
                            label = "$marker ${item.serverName} · ${item.kind.importLabel()}",
                            modifier = Modifier.fillMaxWidth(),
                            color = SettingsForeground,
                            enabled = item.selectable,
                            onClick = {
                                decisions = decisions + (
                                    item.serverName to decision.nextFor(item.kind)
                                    )
                            },
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
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Cancel", color = SettingsActionForeground, onClick = onDismiss)
                TuiButton(
                    label = "Import selected ($selectedCount)",
                    color = SettingsActionForeground,
                    enabled = preview != null && selectedCount > 0,
                    onClick = {
                        preview?.let { current -> onApply(current.id, decisions) }
                    },
                )
            }
        }
    }
}

internal fun McpImportPreview.defaultImportDecisions(): Map<String, McpImportDecision> =
    items.associate { item ->
        item.serverName to when {
            !item.selectable -> McpImportDecision.Skip
            item.kind == McpImportItemKind.New -> McpImportDecision.Import
            item.kind == McpImportItemKind.Conflict -> McpImportDecision.Replace
            else -> McpImportDecision.Skip
        }
    }

@Composable
private fun McpInputField(
    label: String,
    state: TextInputState,
    width: Int,
    autoFocus: Boolean = false,
) {
    Text(label, color = SettingsForeground)
    TextInput(
        state = state,
        layout = TextInputLayout.create(state.value, width),
        modifier = Modifier.fillMaxWidth(),
        autoFocus = autoFocus,
    )
}

@Composable
private fun rememberInput(initialValue: String = ""): TextInputState =
    remember(initialValue) {
        TextInputState(
            TextInputValue(
                text = initialValue,
                cursorOffset = initialValue.length,
            ),
        )
    }

private fun List<String>.keepValuesText(): String =
    joinToString(";") { name -> "$name=$KeepValueMarker" }

private fun parseSecretEntries(text: String): Map<String, McpSecretDraft> =
    buildMap {
        text
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0) { "Each secret entry must use KEY=value." }
                val name = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1)
                require(name.isNotEmpty()) { "A secret entry name must not be blank." }
                require(name !in this) { "Secret entry names must be unique." }
                put(
                    name, if (value == KeepValueMarker) {
                        McpSecretDraft.Keep
                    } else {
                        McpSecretDraft.Replace(value)
                    }
                )
            }
    }

private fun List<String>.formatArguments(): String =
    joinToString(" ") { argument ->
        if (argument.isNotEmpty() && argument.none { it.isWhitespace() || it in "\"'\\" }) {
            argument
        } else {
            buildString {
                append('"')
                argument.forEach { character ->
                    if (character == '"' || character == '\\') append('\\')
                    append(character)
                }
                append('"')
            }
        }
    }

private fun parseArguments(text: String): List<String> {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var tokenStarted = false

    fun finishToken() {
        if (!tokenStarted) return
        arguments += current.toString()
        current.clear()
        tokenStarted = false
    }

    text.forEach { character ->
        when {
            escaped -> {
                current.append(character)
                tokenStarted = true
                escaped = false
            }

            character == '\\' -> {
                escaped = true
                tokenStarted = true
            }

            quote != null && character == quote -> quote = null
            quote != null -> {
                current.append(character)
                tokenStarted = true
            }

            character == '"' || character == '\'' -> {
                quote = character
                tokenStarted = true
            }

            character.isWhitespace() -> finishToken()
            else -> {
                current.append(character)
                tokenStarted = true
            }
        }
    }
    require(!escaped) { "An argument cannot end with an escape character." }
    require(quote == null) { "An argument quote is not closed." }
    finishToken()
    return arguments
}

private fun String.toOptionalSecretDraft(): McpSecretDraft? =
    when (this) {
        "" -> null
        KeepValueMarker -> McpSecretDraft.Keep
        else -> McpSecretDraft.Replace(this)
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

private fun McpImportDecision.nextFor(kind: McpImportItemKind): McpImportDecision =
    when (kind) {
        McpImportItemKind.New ->
            if (this == McpImportDecision.Skip) McpImportDecision.Import else McpImportDecision.Skip

        McpImportItemKind.Conflict ->
            if (this == McpImportDecision.Skip) McpImportDecision.Replace else McpImportDecision.Skip

        McpImportItemKind.Unsupported -> McpImportDecision.Skip
    }

private const val KeepValueMarker: String = "<keep>"
private const val McpEditorMaximumWidth: Int = 84
private const val McpDetailsMaximumWidth: Int = 84
private const val McpDeleteMaximumWidth: Int = 56
private const val McpImportMaximumWidth: Int = 76
private const val McpImportMaximumHeight: Int = 28
