package io.github.stream29.kodex.mcp.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the application-wide set of configured MCP clients.
 *
 * Implementations retain ownership of client and connection lifetimes. Enabled
 * configurations remain present while connecting or failed so consumers can
 * observe their state and request reconnects.
 */
public interface McpService : AutoCloseable {
    /** Current enabled clients keyed by their exact global-settings server name. */
    public val clients: StateFlow<Map<String, McpClient>>

    /** Latest sanitized persistent/runtime authentication state by server name. */
    public val authentication: StateFlow<Map<String, McpAuthenticationState>>

    /**
     * Discards one server's current owner and catalog, then reconciles it from
     * the latest settings snapshot.
     *
     * This is used when a management operation semantically replaces a server
     * even if its resulting connection identity is structurally unchanged.
     */
    public suspend fun invalidate(serverName: String): Unit

    /**
     * Refreshes catalogs through healthy connections.
     *
     * Each successful catalog replaces that client's published generation.
     * Failures retain the previous generation; a detected connection loss also
     * updates that client's connection state.
     */
    public suspend fun refresh()
}
