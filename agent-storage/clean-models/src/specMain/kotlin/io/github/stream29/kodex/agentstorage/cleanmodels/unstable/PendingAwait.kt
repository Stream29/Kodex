package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `await_event` call waiting for user input, a schedule trigger, or watched terminal exit. */
@Serializable
@SerialName("await_event")
public data class PendingAwait(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: AwaitEventArguments,
) : PendingToolCall {
    override val toolName: String = "await_event"
    override val toolNamespace: String? = null

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "await_event",
                serializer = AwaitEventArguments.serializer(),
                arguments = arguments,
            ),
        )
}
