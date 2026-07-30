package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.openai.SearchCommands
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Local `web.run` call waiting for execution. */
@Serializable
@SerialName("web_search")
public data class PendingWebSearchToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val commands: SearchCommands,
) : PendingToolEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "run",
                namespace = "web",
                serializer = SearchCommands.serializer(),
                arguments = commands,
            ),
        )
}
