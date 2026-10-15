package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.shellclient.Shell
import kotlinx.serialization.Serializable

/**
 * Backend section of settings.yml and the GlobalRpc backend settings projection.
 *
 * The complete value crosses RPC, including credentials embedded in MCP configurations.
 * This is not a sanitized projection. Frontend preferences and local-error Hooks are separate.
 * Business notification Hooks and the daemon idle TTL belong to this section.
 * Nested values reuse their existing domain types.
 *
 * All fields are required so clients do not substitute local defaults for backend values.
 * File loading, persistence and migration are not implemented by this model.
 *
 * @property authSource Credential source resolved in the backend environment.
 * @property shell Shell advertised to Agents and used by tools running on the backend.
 * @property contextSources Request-context sources resolved in the backend environment.
 * @property newSession Shared defaults for new Sessions, not a frontend draft or an existing
 * Session's versioned settings.
 * @property sessionTitle Backend automatic-title request controls, not title display preferences.
 * @property mcpServers Backend MCP configurations, including their embedded credentials.
 */
@Serializable
public data class BackendSettings(
    public val authSource: KodexAuthSource,
    public val shell: Shell,
    public val contextSources: AgentContextSourceSettings,
    public val newSession: KodexNewSessionSettings,
    public val sessionTitle: SessionTitleSettings,
    public val mcpServers: Map<String, McpServerConfiguration>,
    public val hooks: List<NotificationHook> = emptyList(),
    public val daemonTtlSeconds: Long = 300,
) {
    init {
        require(daemonTtlSeconds == -1L || daemonTtlSeconds > 0) { "Daemon TTL must be positive or -1." }
        require(hooks.map { it.name }.distinct().size == hooks.size) { "Backend Hook names must be unique." }
    }
}
