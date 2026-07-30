package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.utils.coroutines.runCatchingCancellable
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.schema.json.AdditionalPropertiesConstraint
import kotlinx.schema.json.ArrayPropertyDefinition
import kotlinx.schema.json.BooleanPropertyDefinition
import kotlinx.schema.json.GenericPropertyDefinition
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Tool as SdkTool

internal data class ActiveMcpClient(
    val configuration: McpServerConfiguration,
    val name: String,
    val client: Client,
    val instructions: String,
    val tools: List<SdkTool>,
)

internal fun Map<String, ActiveMcpClient>.toMcpTools(): List<McpTool> =
    values.flatMap { activeClient ->
        activeClient.tools
            .distinctBy(SdkTool::name)
            .map { tool -> McpToolImpl(activeClient, tool) }
    }

private class McpToolImpl(
    private val activeClient: ActiveMcpClient,
    private val tool: SdkTool,
) : McpTool {
    override val serverName: String = activeClient.name
    override val serverInstructions: String = activeClient.instructions

    override val spec: ToolSpec = ResponsesApiNamespace(
        name = "mcp__${activeClient.name.toModelToolName()}",
        description = activeClient.instructions,
        tools = listOf(
            ResponsesApiTool(
                name = tool.name.toModelToolName(),
                description = tool.description.orEmpty(),
                parameters = tool.inputSchema,
                outputSchema = mcpCallToolResultOutputSchema(tool.outputSchema),
            ),
        ),
    )

    override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool {
        val mcpPending = requireNotNull(pending as? PendingMcpToolEvent) {
            "MCP tools require a pending MCP tool event."
        }
        val arguments = try {
            mcpPending.arguments.jsonObject
        } catch (failure: IllegalArgumentException) {
            return completed(
                pending = mcpPending,
                result = failureResult(
                    "MCP arguments for ${mcpPending.callId} must be a JSON object: ${failure.message}",
                ),
            )
        }

        val result = runCatchingCancellable {
            activeClient.client.callTool(
                request = CallToolRequest(
                    CallToolRequestParams(
                        name = tool.name,
                        arguments = arguments,
                    ),
                ),
                options = RequestOptions(),
            ).toOpenAiResult()
        }.getOrElse { failure ->
            failureResult(failure.toMcpFailureMessage(activeClient.name))
        }
        return completed(
            pending = mcpPending,
            result = result,
        )
    }

    override fun close(): Unit = Unit

    private fun completed(
        pending: PendingMcpToolEvent,
        result: CallToolResult,
    ): StableMcpToolEvent =
        StableMcpToolEvent(
            callId = pending.callId,
            itemId = pending.itemId,
            name = pending.name,
            namespace = pending.namespace,
            arguments = pending.arguments,
            result = result,
        )
}

private fun String.toModelToolName(): String =
    map { character ->
        when (character) {
            in 'a'..'z',
            in 'A'..'Z',
            in '0'..'9',
            '_' -> character
            else -> '_'
        }
    }.joinToString(separator = "").ifEmpty { "_" }

/**
 * Mirrors Rust's structured output schema for an MCP `CallToolResult`.
 *
 * @param structuredContentSchema Nullable because MCP tools may omit
 * `outputSchema`; `null` means `structuredContent` accepts any JSON value.
 */
internal fun mcpCallToolResultOutputSchema(
    structuredContentSchema: ObjectPropertyDefinition?,
): ObjectPropertyDefinition =
    ObjectPropertyDefinition(
        properties = mapOf(
            "content" to ArrayPropertyDefinition(items = ObjectPropertyDefinition()),
            "structuredContent" to (structuredContentSchema ?: GenericPropertyDefinition()),
            "isError" to BooleanPropertyDefinition(),
            "_meta" to ObjectPropertyDefinition(),
        ),
        required = listOf("content"),
        additionalProperties = AdditionalPropertiesConstraint.deny(),
    )

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.toOpenAiResult(): CallToolResult =
    CallToolResult(
        content = content.map { block -> McpJson.encodeToJsonElement(block) },
        structuredContent = structuredContent,
        isError = isError,
        meta = meta,
    )

private fun failureResult(message: String): CallToolResult =
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
        is StreamableHttpError -> "MCP server $serverName returned HTTP $code: ${message.orEmpty()}"
        is McpException -> "MCP server $serverName returned error $code: ${message.orEmpty()}"
        else -> message ?: toString()
    }
