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
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason

/** Renders only the sanitized MCP projection supplied by the Global child. */
@Composable
internal fun McpSettingsContent(
    servers: List<McpServerSettingsState>,
    onReconnect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text("MCP servers", color = SettingsForeground)
        if (servers.isEmpty()) {
            Text(
                value = "None configured",
                color = SettingsForeground,
                textStyle = TextStyle.Dim,
            )
        } else {
            servers.forEach { server ->
                McpSettingsRow(
                    serverName = server.serverName,
                    status = server.status.settingsLabel(),
                    onReconnect = if (server.status is McpServerSettingsStatus.Failed) {
                        { onReconnect(server.serverName) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun McpSettingsRow(
    serverName: String,
    status: String,
    onReconnect: (() -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
        Text(
            value = "$serverName: $status",
            color = SettingsForeground,
            textStyle = TextStyle.Dim,
        )
        if (onReconnect != null) {
            Text(" ")
            TuiButton(
                label = "Reconnect",
                color = SettingsForeground,
                onClick = onReconnect,
            )
        }
    }
}

private fun McpServerSettingsStatus.settingsLabel(): String =
    when (this) {
        McpServerSettingsStatus.Disabled -> "Disabled"
        McpServerSettingsStatus.Connecting -> "Connecting"
        is McpServerSettingsStatus.Healthy ->
            "Healthy ($toolCount ${if (toolCount == 1) "tool" else "tools"})"

        is McpServerSettingsStatus.Failed -> "Failed: ${reason.settingsLabel()}"
        McpServerSettingsStatus.Closed -> "Closed"
    }

private fun McpClientFailureReason.settingsLabel(): String =
    when (this) {
        McpClientFailureReason.Transport -> "Transport"
        McpClientFailureReason.Initialization -> "Initialization"
        McpClientFailureReason.ConnectionLost -> "Connection lost"
        McpClientFailureReason.ToolCatalog -> "Tool catalog"
    }
