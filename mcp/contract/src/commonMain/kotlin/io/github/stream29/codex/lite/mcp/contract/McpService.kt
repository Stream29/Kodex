package io.github.stream29.codex.lite.mcp.contract

import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides the currently available MCP tools.
 *
 * Each published [Tool] contains both its model-visible specification and its
 * handler. Implementations retain ownership of MCP connections; published
 * tools must not require callers to manage connection lifetimes.
 */
public interface McpService : AutoCloseable {
    /** Current immutable MCP tool list. */
    public val tools: StateFlow<List<Tool>>

    /** Refreshes the current MCP servers and publishes the resulting tool list. */
    public suspend fun refresh()
}
