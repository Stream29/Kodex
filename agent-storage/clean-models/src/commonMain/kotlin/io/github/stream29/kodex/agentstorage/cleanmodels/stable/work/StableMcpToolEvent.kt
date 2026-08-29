package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableFunctionCall
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
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
) : StableWorkEvent.CompletedTool {
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
