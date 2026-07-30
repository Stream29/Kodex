package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.openai.CallToolResult
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Stable MCP interaction with its complete protocol-native result envelope.
 */
@Serializable
@SerialName("mcp_tool_event")
public data class StableMcpToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val name: String,
    public val namespace: String,
    public val arguments: JsonElement,
    public val result: CallToolResult,
) : StableCleanEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = name,
                namespace = namespace,
                arguments = arguments,
            ),
            ResponseItem.McpToolCallOutput(
                callId = callId,
                output = result,
            ),
        )
}
