package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Deferred client tool search waiting for local execution. */
@Serializable
@SerialName("tool_search")
public data class PendingToolSearchEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: SearchToolCallParams,
) : PendingToolEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.ClientToolSearchCall(
                id = itemId,
                callId = callId,
                arguments = pendingJsonElement(
                    serializer = SearchToolCallParams.serializer(),
                    value = arguments,
                ),
            ),
        )
}
