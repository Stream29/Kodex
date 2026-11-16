package io.github.stream29.kodex.mcp.contract

import kotlinx.coroutines.flow.StateFlow

/** Atomic persistence port shared by the manager and runtime token refresher. */
public interface McpConfigurationStore {
    public val configurations: StateFlow<Map<String, McpServerConfiguration>>

    public suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration>
}

/** Refreshes one initialized OAuth token set without changing client identity. */
public fun interface McpOAuthTokenRefresher {
    public suspend fun refresh(
        configuration: McpOAuthConfiguration.Initialized,
    ): McpOAuthConfiguration.Initialized
}

/**
 * Application-wide MCP sanitized-state observer and runtime action coordinator.
 */
public interface McpManager : AutoCloseable {
    public val servers: StateFlow<List<McpManagedServerState>>

    public suspend fun logout(serverName: String): Unit
    public suspend fun reconnect(serverName: String): Unit

    override fun close(): Unit
}
