package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.mcp.contract.*

/** Frontend-safe MCP projection; no backend manager ownership is transferred to the renderer. */
public fun McpManagedServerState.toSettingsState(): McpServerSettingsState = McpServerSettingsState(
    serverName, transport, enabled, authentication,
    when {
        !enabled -> McpServerSettingsStatus.Disabled
        authentication.isBlocked() || connection == McpClientState.AuthenticationBlocked ->
            McpServerSettingsStatus.AuthenticationBlocked(authentication)
        connection == null || connection == McpClientState.Connecting -> McpServerSettingsStatus.Connecting
        connection == McpClientState.Healthy -> McpServerSettingsStatus.Healthy(toolCount)
        connection is McpClientState.Failed -> McpServerSettingsStatus.Failed((connection as McpClientState.Failed).reason)
        connection == McpClientState.Closed -> McpServerSettingsStatus.Closed
        else -> McpServerSettingsStatus.Connecting
    },
    headerNames, environmentNames, oauth, streamableHttpUrl, stdioCommand, stdioArguments, stdioWorkingDirectory,
)
private fun McpAuthenticationState.isBlocked(): Boolean = when (this) {
    McpAuthenticationState.NotConfigured, McpAuthenticationState.Authorized -> false
    else -> true
}
