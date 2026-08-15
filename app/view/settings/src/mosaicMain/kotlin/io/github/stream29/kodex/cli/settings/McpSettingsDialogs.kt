package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.mcp.contract.DefaultMcpOAuthRedirectUri
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
                            val clientId = oauthClientId.value.text.trim()
                            val redirectUri = oauthRedirect.value.text.trim()
                            require(clientId.isNotEmpty()) { "OAuth client id is required." }
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
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                value = if (existing == null) "Add MCP server" else "Edit MCP server",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TextStyle.Bold,
            )
            McpInputField("Server name", name, width, autoFocus = true)
            Text("Transport", color = SettingsForeground)
            Row {
                McpTransportKind.entries.forEachIndexed { index, option ->
                    if (index > 0) Text(" ")
                    TuiButton(
                        label = option.editorLabel(),
                        modifier = Modifier.background(
                            if (option == transport) {
                                SettingsSelectionBackground
                            } else {
                                SettingsHomeBackground
                            },
                        ),
                        color = SettingsForeground,
                        onClick = { transport = option },
                    )
                }
            }
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
                Text("OAuth", color = SettingsForeground)
                Row {
                    listOf(true, false).forEachIndexed { index, enabled ->
                        if (index > 0) Text(" ")
                        TuiButton(
                            label = if (enabled) "Enabled" else "None",
                            modifier = Modifier.background(
                                if (enabled == oauthEnabled) {
                                    SettingsSelectionBackground
                                } else {
                                    SettingsHomeBackground
                                },
                            ),
                            color = SettingsForeground,
                            onClick = { oauthEnabled = enabled },
                        )
                    }
                }
                if (oauthEnabled) {
                    McpInputField("OAuth client id", oauthClientId, width)
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
                Text(message, color = SettingsForeground, textStyle = TextStyle.Bold)
            }
            Row(modifier = Modifier.fillMaxWidth().background(SettingsActionBackground)) {
                TuiButton(label = "Save", color = SettingsForeground, onClick = ::save)
                Text(" ")
                TuiButton(label = "Cancel", color = SettingsForeground, onClick = onDismiss)
            }
        }
    }
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
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                "Delete MCP server",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TextStyle.Bold,
            )
            Text("Delete '${server.serverName}'?", color = SettingsForeground)
            Row(modifier = Modifier.fillMaxWidth().background(SettingsActionBackground)) {
                TuiButton(label = "Delete", color = SettingsForeground, onClick = onConfirm)
                Text(" ")
                TuiButton(label = "Cancel", color = SettingsForeground, onClick = onDismiss)
            }
        }
    }
}

/** Explicit preview/filter/selection flow; preview itself never writes settings. */
@Composable
internal fun BoxScope.McpImportDialog(
    preview: McpImportPreview?,
    onPreview: (String) -> Unit,
    onApply: (Long, Map<String, McpImportDecision>) -> Unit,
    onDismiss: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, McpImportMaximumWidth)
    val filter = rememberInput(preview?.filter.orEmpty())
    var decisions by remember(preview?.id) {
        mutableStateOf<Map<String, McpImportDecision>>(emptyMap())
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                "Import MCP from Codex",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TextStyle.Bold,
            )
            McpInputField("Filter by server name", filter, width, autoFocus = true)
            Row {
                TuiButton(
                    label = "Preview",
                    color = SettingsForeground,
                    onClick = { onPreview(filter.value.text) },
                )
                Text(" ")
                TuiButton(label = "Cancel", color = SettingsForeground, onClick = onDismiss)
            }
            preview?.let { current ->
                if (current.items.isEmpty()) {
                    Text("No matching Codex MCP servers.", color = SettingsForeground)
                }
                current.items.forEach { item ->
                    val decision = decisions[item.serverName] ?: McpImportDecision.Skip
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            value = "${item.serverName}: ${item.kind.previewLabel()}",
                            color = SettingsForeground,
                        )
                        Text(" ")
                        TuiButton(
                            label = decision.previewLabel(),
                            color = SettingsForeground,
                            enabled = item.selectable,
                            onClick = {
                                decisions = decisions + (
                                    item.serverName to decision.nextFor(item.kind)
                                    )
                            },
                        )
                    }
                    item.detail?.let { detail ->
                        Text(
                            value = "  $detail",
                            color = SettingsForeground,
                            textStyle = TextStyle.Dim,
                        )
                    }
                }
                TuiButton(
                    label = "Apply selected",
                    color = SettingsForeground,
                    enabled = decisions.values.any { it != McpImportDecision.Skip },
                    onClick = { onApply(current.id, decisions) },
                )
            }
        }
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
            put(name, if (value == KeepValueMarker) {
                McpSecretDraft.Keep
            } else {
                McpSecretDraft.Replace(value)
            })
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

private fun McpImportItemKind.previewLabel(): String =
    when (this) {
        McpImportItemKind.New -> "new"
        McpImportItemKind.Conflict -> "same-name conflict"
        McpImportItemKind.Unsupported -> "unsupported"
    }

private fun McpImportDecision.previewLabel(): String =
    when (this) {
        McpImportDecision.Skip -> "Skip"
        McpImportDecision.Import -> "Import"
        McpImportDecision.Replace -> "Replace"
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
private const val McpDeleteMaximumWidth: Int = 56
private const val McpImportMaximumWidth: Int = 76
