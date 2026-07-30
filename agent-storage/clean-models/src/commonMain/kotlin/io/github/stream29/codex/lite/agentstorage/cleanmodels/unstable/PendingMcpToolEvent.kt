package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** MCP function call with decoded JSON arguments. */
@Serializable
@SerialName("mcp")
public data class PendingMcpToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val name: String,
    public val namespace: String,
    public val arguments: JsonElement,
) : PendingToolEvent {
    override val toolName: String
        get() = name
    override val toolNamespace: String
        get() = namespace

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = name,
                namespace = namespace,
                arguments = arguments,
            ),
        )
}
