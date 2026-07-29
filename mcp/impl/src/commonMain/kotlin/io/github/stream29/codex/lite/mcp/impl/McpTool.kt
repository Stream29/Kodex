package io.github.stream29.codex.lite.mcp.impl

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.codex.lite.mcp.contract.McpServerConfiguration
import io.github.stream29.codex.lite.mcp.contract.McpTool
import io.github.stream29.codex.lite.openai.CallToolResult
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.ToolCallResult
import io.github.stream29.codex.lite.utils.coroutines.runCatchingCancellable
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

    override suspend fun handle(call: ResponseItem.ToolCall): ToolCallResult {
        val arguments = when (call) {
            is ResponseItem.FunctionCall -> when (
                val parsed = call.arguments.toArgumentsOrFailure(call.callId)
            ) {
                is ParsedArguments.Success -> parsed.value
                is ParsedArguments.Failure -> {
                    return completed(
                        arguments = JsonPrimitive(call.arguments),
                        output = call.failure(parsed.message),
                    )
                }
            }

            is ResponseItem.CustomToolCall -> {
                return completed(
                    arguments = JsonPrimitive(call.input),
                    output = call.failure("MCP tools accept JSON function arguments."),
                )
            }

            is ResponseItem.ClientToolSearchCall -> error("Client tool-search calls are handled by CodexToolRuntime.")
        }

        val output = runCatchingCancellable {
            val result = activeClient.client.callTool(
                request = CallToolRequest(
                    CallToolRequestParams(
                        name = tool.name,
                        arguments = arguments,
                    ),
                ),
                options = RequestOptions(),
            )
            ResponseItem.McpToolCallOutput(
                callId = call.callId,
                output = result.toOpenAiResult(),
            )
        }.getOrElse { failure ->
            call.failure(failure.toMcpFailureMessage(activeClient.name))
        }
        return completed(
            arguments = arguments,
            output = output,
        )
    }

    override fun close(): Unit = Unit

    private fun completed(
        arguments: JsonElement,
        output: ResponseItem.McpToolCallOutput,
    ): ToolCallResult =
        output to StableJsonToolEvent(
            name = tool.name,
            namespace = activeClient.name,
            arguments = arguments,
            result = McpJson.encodeToJsonElement(CallToolResult.serializer(), output.output),
            success = output.output.isError?.not(),
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

private sealed interface ParsedArguments {
    data class Success(val value: JsonObject) : ParsedArguments
    data class Failure(val message: String) : ParsedArguments
}

private fun String.toArgumentsOrFailure(callId: String): ParsedArguments {
    if (isBlank()) return ParsedArguments.Success(JsonObject(emptyMap()))
    return try {
        ParsedArguments.Success(McpJson.parseToJsonElement(this).jsonObject)
    } catch (failure: SerializationException) {
        ParsedArguments.Failure("Invalid MCP arguments for $callId: ${failure.message}")
    } catch (failure: IllegalArgumentException) {
        ParsedArguments.Failure("MCP arguments for $callId must be a JSON object: ${failure.message}")
    }
}

private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.toOpenAiResult(): CallToolResult =
    CallToolResult(
        content = content.map { block -> McpJson.encodeToJsonElement(block) },
        structuredContent = structuredContent,
        isError = isError,
        meta = meta,
    )

private fun ResponseItem.ToolCall.failure(message: String): ResponseItem.McpToolCallOutput =
    ResponseItem.McpToolCallOutput(
        callId = callId,
        output = CallToolResult(
            content = listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(message))
                },
            ),
            isError = true,
        ),
    )

private fun Throwable.toMcpFailureMessage(serverName: String): String =
    when (this) {
        is StreamableHttpError -> "MCP server $serverName returned HTTP $code: ${message.orEmpty()}"
        is McpException -> "MCP server $serverName returned error $code: ${message.orEmpty()}"
        else -> message ?: toString()
    }
