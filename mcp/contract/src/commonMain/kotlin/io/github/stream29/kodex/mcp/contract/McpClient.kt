package io.github.stream29.kodex.mcp.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Application-managed client for one configured MCP server.
 *
 * Each client generation has a fixed tool catalog. Connection loss only updates
 * [state]. An explicit refresh, successful reconnect, or settings update
 * publishes a replacement client generation with a refreshed catalog.
 *
 * [McpService] owns the client's lifetime. Consumers observe [state] and may
 * request state transitions, but do not own or close the underlying SDK client.
 */
public interface McpClient {
    /** Name of the server in global settings. */
    public val serverName: String

    /** Current connection health. */
    public val state: StateFlow<McpClientState>

    /**
     * Returns the complete tool catalog fixed to this client generation.
     *
     * Each returned tool dispatches through this managed client. It performs the
     * remote call only while [state] is [McpClientState.Healthy]; otherwise it
     * returns an MCP server-unavailable tool result.
     */
    public fun listTools(): List<McpTool>

    /**
     * Replaces the current connection and refreshes its catalog.
     *
     * The previous catalog remains published during the operation. Success
     * publishes a replacement client generation atomically; failure retains the
     * previous catalog and publishes [McpClientState.Failed].
     */
    public suspend fun reconnect()
}

/** Observable connection lifecycle for one [McpClient]. */
public sealed interface McpClientState {
    /** The first connection or a replacement connection is being established. */
    public data object Connecting : McpClientState

    /** The connection is available for tool calls. */
    public data object Healthy : McpClientState

    /** No connection is currently available for tool calls. */
    public data class Failed(
        public val reason: McpClientFailureReason,
    ) : McpClientState

    /** The owning service has permanently released this client. */
    public data object Closed : McpClientState
}

/** Stable failure category for rendering and agent-facing unavailable results. */
public enum class McpClientFailureReason {
    /** The configured transport could not be opened. */
    Transport,

    /** The MCP initialization handshake failed. */
    Initialization,

    /** A previously healthy transport was lost. */
    ConnectionLost,

    /** The initial or reconnected tool catalog could not be loaded. */
    ToolCatalog,
}
