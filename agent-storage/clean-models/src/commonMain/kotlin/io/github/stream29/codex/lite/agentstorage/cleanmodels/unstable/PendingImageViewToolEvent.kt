package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Local `view_image` call waiting for execution. */
@Serializable
@SerialName("image_view")
public data class PendingImageViewToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: ViewImageToolArguments,
) : PendingToolEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "view_image",
                serializer = ViewImageToolArguments.serializer(),
                arguments = arguments,
            ),
        )
}
