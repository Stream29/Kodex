package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Local `read_media` call waiting for execution. */
@Serializable
@SerialName("read_media")
public data class PendingReadMedia(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: ReadMediaToolArguments,
) : PendingToolCall {
    override val toolName: String = "read_media"
    override val toolNamespace: String? = null

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "read_media",
                serializer = ReadMediaToolArguments.serializer(),
                arguments = arguments,
            ),
        )
}
