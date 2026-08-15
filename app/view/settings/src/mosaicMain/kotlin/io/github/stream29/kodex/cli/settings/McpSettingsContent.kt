package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.app.settings.contract.McpServerSettingsStatus
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpTransportKind

/** Full MCP management entry point backed only by sanitized manager state. */
@Composable
internal fun McpSettingsContent(
    servers: List<McpServerSettingsState>,
    onAdd: () -> Unit,
    onEdit: (McpServerSettingsState) -> Unit,
    onDelete: (McpServerSettingsState) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onLogin: (String) -> Unit,
    onCancelLogin: (String) -> Unit,
    onLogout: (String) -> Unit,
    onImport: () -> Unit,
    onReconnect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text("MCP servers", color = SettingsForeground)
        Row {
            TuiButton(
                label = "Add",
                color = SettingsForeground,
                onClick = onAdd,
            )
            Text(" ")
            TuiButton(
                label = "Import from Codex",
                color = SettingsForeground,
                onClick = onImport,
            )
        }
        if (servers.isEmpty()) {
            Text(
                value = "None configured",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        } else {
            servers.forEach { server ->
                McpSettingsRow(
                    server = server,
                    onEdit = { onEdit(server) },
                    onDelete = { onDelete(server) },
                    onSetEnabled = {
                        onSetEnabled(server.serverName, !server.enabled)
                    },
                    onLogin = { onLogin(server.serverName) },
                    onCancelLogin = { onCancelLogin(server.serverName) },
                    onLogout = { onLogout(server.serverName) },
                    onReconnect = { onReconnect(server.serverName) },
                )
            }
        }
    }
}

@Composable
private fun McpSettingsRow(
    server: McpServerSettingsState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: () -> Unit,
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    onReconnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text(
            value = "${server.serverName} · ${server.transport.settingsLabel()} · " +
                server.status.settingsLabel(),
            color = SettingsForeground,
            textStyle = TextStyle.Dim,
        )
        server.streamableHttpUrl?.let { url ->
            Text(value = url, color = SettingsForeground, textStyle = TextStyle.Dim)
        }
        server.stdioCommand?.let { command ->
            Text(
                value = buildString {
                    append(command)
                    if (server.stdioArguments.isNotEmpty()) {
                        append(' ')
                        append(server.stdioArguments.joinToString(" "))
                    }
                },
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        }
        if (server.headerNames.isNotEmpty()) {
            Text(
                value = "Headers: ${server.headerNames.joinToString()} (values hidden)",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        }
        if (server.environmentNames.isNotEmpty()) {
            Text(
                value = "Environment: ${server.environmentNames.joinToString()} (values hidden)",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        }
        Row {
            TuiButton(
                label = if (server.enabled) "Disable" else "Enable",
                color = SettingsForeground,
                onClick = onSetEnabled,
            )
            Text(" ")
            TuiButton(label = "Edit", color = SettingsForeground, onClick = onEdit)
            Text(" ")
            TuiButton(label = "Delete", color = SettingsForeground, onClick = onDelete)
            when (server.authentication) {
                McpAuthenticationState.LoginRequired,
                McpAuthenticationState.ReauthorizationRequired,
                is McpAuthenticationState.Failed,
                    -> {
                    Text(" ")
                    TuiButton(label = "Log in", color = SettingsForeground, onClick = onLogin)
                }

                McpAuthenticationState.Authorized,
                McpAuthenticationState.Refreshing,
                    -> {
                    Text(" ")
                    TuiButton(label = "Log out", color = SettingsForeground, onClick = onLogout)
                }

                McpAuthenticationState.Authorizing -> {
                    Text(" ")
                    TuiButton(
                        label = "Cancel login",
                        color = SettingsForeground,
                        onClick = onCancelLogin,
                    )
                }

                McpAuthenticationState.NotConfigured -> Unit
            }
            if (server.status is McpServerSettingsStatus.Failed) {
                Text(" ")
                TuiButton(
                    label = "Reconnect",
                    color = SettingsForeground,
                    onClick = onReconnect,
                )
            }
        }
    }
}

private fun McpServerSettingsStatus.settingsLabel(): String =
    when (this) {
        McpServerSettingsStatus.Disabled -> "Disabled"
        is McpServerSettingsStatus.AuthenticationBlocked ->
            "Authentication: ${state.settingsLabel()}"

        McpServerSettingsStatus.Connecting -> "Connecting"
        is McpServerSettingsStatus.Healthy ->
            "Healthy ($toolCount ${if (toolCount == 1) "tool" else "tools"})"

        is McpServerSettingsStatus.Failed -> "Failed: ${reason.settingsLabel()}"
        McpServerSettingsStatus.Closed -> "Closed"
    }

private fun McpAuthenticationState.settingsLabel(): String =
    when (this) {
        McpAuthenticationState.NotConfigured -> "Not configured"
        McpAuthenticationState.LoginRequired -> "Login required"
        McpAuthenticationState.ReauthorizationRequired -> "Login required again"
        McpAuthenticationState.Authorizing -> "Waiting for browser"
        McpAuthenticationState.Authorized -> "Authorized"
        McpAuthenticationState.Refreshing -> "Refreshing"
        is McpAuthenticationState.Failed -> message
    }

private fun McpTransportKind.settingsLabel(): String =
    when (this) {
        McpTransportKind.StreamableHttp -> "Streamable HTTP"
        McpTransportKind.Stdio -> "stdio"
    }

private fun McpClientFailureReason.settingsLabel(): String =
    when (this) {
        McpClientFailureReason.Transport -> "Transport"
        McpClientFailureReason.Initialization -> "Initialization"
        McpClientFailureReason.ConnectionLost -> "Connection lost"
        McpClientFailureReason.ToolCatalog -> "Tool catalog"
    }
