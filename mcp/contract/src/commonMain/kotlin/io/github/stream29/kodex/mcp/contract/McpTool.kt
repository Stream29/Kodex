package io.github.stream29.kodex.mcp.contract

import io.github.stream29.kodex.tool.contract.Tool

/**
 * Executable MCP tool with the source metadata required by ToolSearch.
 *
 * A published tool survives transient connection loss. Its handler dispatches
 * through the owning managed client and returns a tool error when that server is
 * unavailable instead of invoking a retired connection generation.
 */
public interface McpTool : Tool {
    /** Exact configured MCP server name used as the ToolSearch source label. */
    public val serverName: String

    /** Server instructions; an empty value means the source has no description. */
    public val serverInstructions: String
}
