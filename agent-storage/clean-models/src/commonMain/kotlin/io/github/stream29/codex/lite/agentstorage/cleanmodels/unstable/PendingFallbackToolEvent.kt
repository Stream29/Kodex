package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Dynamic function call with decoded JSON arguments. */
@Serializable
@SerialName("function")
public data class PendingFunctionToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val name: String,
    public val namespace: String? = null,
    public val arguments: JsonElement,
) : PendingToolEvent {
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

/** Dynamic custom-tool call with freeform text input. */
@Serializable
@SerialName("custom")
public data class PendingCustomToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val name: String,
    public val namespace: String? = null,
    public val input: String,
) : PendingToolEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.CustomToolCall(
                id = itemId,
                callId = callId,
                name = name,
                namespace = namespace,
                input = input,
            ),
        )
}
