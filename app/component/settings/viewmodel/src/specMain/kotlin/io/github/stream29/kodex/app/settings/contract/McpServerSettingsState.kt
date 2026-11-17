package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpOAuthSummary
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.io.files.Path

/**
 * Sanitized application-wide MCP row, preserving the existing public package and shape.
 * Config and runtime streams are not an atomic pair. Name/transport/status form the list row;
 * details may display URL, command, arguments, cwd, secret NAMES and OAuth summary, never values.
 * Saved configuration, an admitted write or browser opening must not synthesize Healthy.
 * @throws IllegalArgumentException [serverName] is blank.
 */
public data class McpServerSettingsState(
    public val serverName: String,
    public val transport: McpTransportKind,
    public val enabled: Boolean,
    public val authentication: McpAuthenticationState,
    public val status: McpServerSettingsStatus,
    public val headerNames: List<String> = emptyList(),
    public val environmentNames: List<String> = emptyList(),
    public val oauth: McpOAuthSummary? = null,
    public val streamableHttpUrl: String? = null,
    public val stdioCommand: String? = null,
    public val stdioArguments: List<String> = emptyList(),
    public val stdioWorkingDirectory: Path? = null,
) {
    init {
        require(serverName.isNotBlank()) { "An MCP Settings server name must not be blank." }
    }
}

/**
 * Runtime-only renderer branches: Disabled, Connecting and Closed show those labels; blocked
 * shows its authentication label; Healthy shows tool count; Failed shows its bounded reason
 * and offers Reconnect in details. No branch grants permission to overwrite configuration.
 */
public sealed interface McpServerSettingsStatus {
    public data object Disabled : McpServerSettingsStatus
    public data class AuthenticationBlocked(
        public val state: McpAuthenticationState,
    ) : McpServerSettingsStatus
    public data object Connecting : McpServerSettingsStatus
    /** @throws IllegalArgumentException [toolCount] is negative. */
    public data class Healthy(public val toolCount: Int) : McpServerSettingsStatus {
        init {
            require(toolCount >= 0) { "An MCP Settings tool count must not be negative." }
        }
    }
    public data class Failed(public val reason: McpClientFailureReason) : McpServerSettingsStatus
    public data object Closed : McpServerSettingsStatus
}
