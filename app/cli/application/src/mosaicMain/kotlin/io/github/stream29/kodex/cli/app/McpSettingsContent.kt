package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration

/** Renders only sanitized MCP lifecycle data; transport configuration stays hidden. */
@Composable
internal fun McpSettingsContent(
    configurations: Map<String, McpServerConfiguration>,
    clients: Map<String, McpClient>,
    onReconnect: (McpClient) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text("MCP servers", color = SettingsDialogForeground)
        if (configurations.isEmpty()) {
            Text(
                value = "None configured",
                color = SettingsDialogForeground,
                textStyle = TextStyle.Dim,
            )
        } else {
            configurations.entries
                .sortedBy(Map.Entry<String, McpServerConfiguration>::key)
                .forEach { (serverName, configuration) ->
                    key(serverName) {
                        when {
                            !configuration.enabled -> McpSettingsRow(
                                serverName = serverName,
                                status = "Disabled",
                            )

                            clients[serverName] == null -> McpSettingsRow(
                                serverName = serverName,
                                status = "Connecting",
                            )

                            else -> McpClientSettingsRow(
                                client = checkNotNull(clients[serverName]),
                                onReconnect = onReconnect,
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun McpClientSettingsRow(
    client: McpClient,
    onReconnect: (McpClient) -> Unit,
) {
    val state by client.state.collectAsState()
    McpSettingsRow(
        serverName = client.serverName,
        status = state.settingsLabel(client.listTools().size),
        onReconnect = if (state is McpClientState.Failed) {
            { onReconnect(client) }
        } else {
            null
        },
    )
}

@Composable
private fun McpSettingsRow(
    serverName: String,
    status: String,
    onReconnect: (() -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogHomeBackground)) {
        Text(
            value = "$serverName: $status",
            color = SettingsDialogForeground,
            textStyle = TextStyle.Dim,
        )
        if (onReconnect != null) {
            Text(" ")
            TuiButton(
                label = "Reconnect",
                color = SettingsDialogForeground,
                onClick = onReconnect,
            )
        }
    }
}

private fun McpClientState.settingsLabel(toolCount: Int): String =
    when (this) {
        McpClientState.Connecting -> "Connecting"
        McpClientState.Healthy -> "Healthy ($toolCount ${if (toolCount == 1) "tool" else "tools"})"
        is McpClientState.Failed -> "Failed: ${reason.settingsLabel()}"
        McpClientState.Closed -> "Closed"
    }

private fun McpClientFailureReason.settingsLabel(): String =
    when (this) {
        McpClientFailureReason.Transport -> "Transport"
        McpClientFailureReason.Initialization -> "Initialization"
        McpClientFailureReason.ConnectionLost -> "Connection lost"
        McpClientFailureReason.ToolCatalog -> "Tool catalog"
    }
