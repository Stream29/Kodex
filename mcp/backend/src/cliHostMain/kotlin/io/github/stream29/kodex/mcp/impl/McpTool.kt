package io.github.stream29.kodex.mcp.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import io.modelcontextprotocol.kotlin.sdk.types.Tool as SdkTool

private val mcpToolLogger = KotlinLogging.logger {}

internal class McpToolImpl(
    private val owner: McpClientImpl,
    override val serverInstructions: String,
    private val tool: SdkTool,
) : McpTool {
    override val serverName: String = owner.serverName
    override val toolName: String = tool.name
    override val description: String = tool.description.orEmpty()
    override val inputSchema: ObjectPropertyDefinition = tool.inputSchema
    override val outputSchema: ObjectPropertyDefinition? = tool.outputSchema

    override suspend fun call(arguments: JsonObject): CallToolResult =
        when (
            val call = owner.call { client ->
                client.callTool(
                    request = CallToolRequest(
                        CallToolRequestParams(
                            name = tool.name,
                            arguments = arguments,
                        ),
                    ),
                    options = RequestOptions(),
                )
            }
        ) {
            is McpClientCallResult.Success -> call.value.toOpenAiResult()
            is McpClientCallResult.Failure -> {
                if (call.cause !is Exception) throw call.cause
                mcpToolLogger.error(call.cause) {
                    "MCP tool execution failed for ${owner.serverName}/${tool.name}."
                }
                failureResult(call.cause.toMcpFailureMessage(owner.serverName))
            }

            is McpClientCallResult.Unavailable ->
                failureResult(call.state.toUnavailableMessage(owner.serverName))
        }
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.toOpenAiResult(): CallToolResult =
    CallToolResult(
        content = content.map { block -> McpJson.encodeToJsonElement(block) },
        structuredContent = structuredContent,
        isError = isError,
        meta = meta,
    )

internal fun failureResult(message: String): CallToolResult =
    CallToolResult(
        content = listOf(
            buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(message))
            },
        ),
        isError = true,
    )

private fun Throwable.toMcpFailureMessage(serverName: String): String =
    when (this) {
        is StreamableHttpError -> "MCP server $serverName returned HTTP $code."
        is McpException -> "MCP server $serverName returned error $code."
        else -> "MCP server $serverName tool execution failed."
    }

private fun McpClientState.toUnavailableMessage(serverName: String): String =
    when (this) {
        McpClientState.AuthenticationBlocked ->
            "MCP server $serverName requires authorization and is not available."

        McpClientState.Connecting -> "MCP server $serverName is connecting and is not available."
        McpClientState.Closed -> "MCP server $serverName is closed and is not available."
        is McpClientState.Failed ->
            "MCP server $serverName is not available: ${reason.agentMessage()}."

        McpClientState.Healthy -> "MCP server $serverName is not available."
    }

private fun McpClientFailureReason.agentMessage(): String =
    when (this) {
        McpClientFailureReason.Transport -> "its transport could not be opened"
        McpClientFailureReason.Initialization -> "initialization failed"
        McpClientFailureReason.ConnectionLost -> "the connection was lost"
        McpClientFailureReason.ToolCatalog -> "its tool catalog could not be loaded"
    }
