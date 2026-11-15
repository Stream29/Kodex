package io.github.stream29.kodex.mcp.contract

import io.github.stream29.kodex.openai.CallToolResult
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Executable MCP tool published by a managed [McpClient].
 *
 * A published tool survives transient connection loss. [call] dispatches
 * through the owning managed client and returns an error [CallToolResult] when
 * that server is unavailable instead of invoking a retired connection generation.
 */
public interface McpTool {
    /** Exact configured MCP server name. */
    public val serverName: String

    /** Server instructions; an empty value means the source has no description. */
    public val serverInstructions: String

    /** Exact MCP tool name published by the server. */
    public val toolName: String

    /** Human-readable tool description; an empty value means none was provided. */
    public val description: String

    /** JSON Schema for the tool's input object. */
    public val inputSchema: ObjectPropertyDefinition

    /** Optional JSON Schema for structured output content. */
    public val outputSchema: ObjectPropertyDefinition?

    /** Invokes the tool through the owning [McpClient] with [arguments]. */
    public suspend fun call(arguments: JsonObject = JsonObject(emptyMap())): CallToolResult
}

/**
 * Serializable catalog metadata for a published [McpTool] exposed via
 * `GlobalRpc.listMcpTools()`, `kodex tool list`, `kodex mcp status`, and dynamic
 * `kodex <server> <tool>` CLI domains.
 */
@Serializable
public data class McpCatalogTool(
    @SerialName("server_name")
    public val serverName: String,
    @SerialName("server_instructions")
    public val serverInstructions: String = "",
    @SerialName("tool_name")
    public val toolName: String,
    public val description: String = "",
    @SerialName("input_schema")
    public val inputSchema: ObjectPropertyDefinition,
    @SerialName("output_schema")
    public val outputSchema: ObjectPropertyDefinition? = null,
)
