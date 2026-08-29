package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolJson
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableFunctionCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableTextOutput
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Completed function tool with decoded JSON arguments and result. */
@Serializable
@SerialName("json_tool_event")
public data class StableJsonToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val name: String,
    public val namespace: String? = null,
    public val arguments: JsonElement,
    public val result: JsonElement,
    public val success: Boolean? = null,
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
            stableTextOutput(
                callId = callId,
                text = StableToolJson.encodeToString(JsonElement.serializer(), result),
                success = success,
            ),
        )
}

/** Completed function tool with decoded JSON arguments and a text result. */
@Serializable
@SerialName("text_tool_event")
public data class StableTextToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val name: String,
    public val namespace: String? = null,
    public val arguments: JsonElement,
    public val result: String,
    public val success: Boolean? = null,
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
            stableTextOutput(
                callId = callId,
                text = result,
                success = success,
            ),
        )
}

/** Completed custom tool retained when no dedicated semantic event applies. */
@Serializable
@SerialName("custom_tool_event")
public data class StableCustomToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val name: String,
    public val namespace: String? = null,
    public val input: String,
    public val result: FunctionCallOutputPayload,
    public val success: Boolean? = null,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.CustomToolCall(
                id = itemId,
                callId = callId,
                name = name,
                namespace = namespace,
                input = input,
            ),
            ResponseItem.CustomToolCallOutput(
                callId = callId,
                output = result.copy(success = success),
            ),
        )
}
