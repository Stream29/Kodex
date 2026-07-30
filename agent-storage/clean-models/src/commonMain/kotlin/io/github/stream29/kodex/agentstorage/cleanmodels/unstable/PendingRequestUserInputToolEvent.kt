package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `request_user_input` call waiting for completion. */
@Serializable
@SerialName("request_user_input")
public data class PendingRequestUserInputToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: RequestUserInputArgs,
) : PendingToolEvent {
    override val toolName: String = "request_user_input"
    override val toolNamespace: String? = null

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "request_user_input",
                serializer = RequestUserInputArgs.serializer(),
                arguments = arguments,
            ),
        )
}
