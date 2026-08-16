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
    onOpenDetails: (McpServerSettingsState) -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsSectionHeaderBackground),
        ) {
            Text("MCP servers ", color = SettingsForeground)
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
                TuiButton(
                    label = "${server.serverName} · ${server.status.settingsLabel()}",
                    modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground),
                    color = SettingsForeground,
                    onClick = { onOpenDetails(server) },
                )
            }
        }
    }
}

internal fun McpServerSettingsStatus.settingsLabel(): String =
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

internal fun McpAuthenticationState.settingsLabel(): String =
    when (this) {
        McpAuthenticationState.NotConfigured -> "Not configured"
        McpAuthenticationState.LoginRequired -> "Login required"
        McpAuthenticationState.ReauthorizationRequired -> "Login required again"
        McpAuthenticationState.Authorizing -> "Waiting for browser"
        McpAuthenticationState.Authorized -> "Authorized"
        McpAuthenticationState.Refreshing -> "Refreshing"
        is McpAuthenticationState.Failed -> message
    }

internal fun McpTransportKind.settingsLabel(): String =
    when (this) {
        McpTransportKind.StreamableHttp -> "Streamable HTTP"
        McpTransportKind.Stdio -> "stdio"
    }

internal fun McpClientFailureReason.settingsLabel(): String =
    when (this) {
        McpClientFailureReason.Transport -> "Transport"
        McpClientFailureReason.Initialization -> "Initialization"
        McpClientFailureReason.ConnectionLost -> "Connection lost"
        McpClientFailureReason.ToolCatalog -> "Tool catalog"
    }
