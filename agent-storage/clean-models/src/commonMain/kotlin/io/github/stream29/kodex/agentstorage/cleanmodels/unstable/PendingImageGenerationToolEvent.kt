package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Local `image_gen.imagegen` call waiting for execution. */
@Serializable
@SerialName("image_generation")
public data class PendingImageGenerationToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: ImageGenToolArguments,
) : PendingToolEvent {
    override val toolName: String = "imagegen"
    override val toolNamespace: String = "image_gen"

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "imagegen",
                namespace = "image_gen",
                serializer = ImageGenToolArguments.serializer(),
                arguments = arguments,
            ),
        )
}
