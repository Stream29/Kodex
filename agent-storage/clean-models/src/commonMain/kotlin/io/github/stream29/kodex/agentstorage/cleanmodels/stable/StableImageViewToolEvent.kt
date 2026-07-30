package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ImageDetail
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageDetail
import io.github.stream29.kodex.tool.viewimage.ViewImageToolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed `view_image` interaction.
 *
 * @property arguments Tool-native image-view arguments.
 * @property result Image inspection outcome.
 */
@Serializable
@SerialName("image_view_tool_event")
public data class StableImageViewToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val arguments: ViewImageToolArguments,
    public val result: StableImageViewResult,
) : StableCleanEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "view_image",
                serializer = ViewImageToolArguments.serializer(),
                arguments = arguments,
            ),
            stableFunctionOutput(
                callId = callId,
                output = result.toFunctionCallOutputPayload(),
            ),
        )
}

/** Completed outcome of an image inspection. */
@Serializable
public sealed interface StableImageViewResult {
    /** Image bytes were loaded and exposed through [imageUrl]. */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val output: ViewImageToolOutput,
    ) : StableImageViewResult

    /** The image could not be loaded. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableImageViewResult
}

private fun StableImageViewResult.toFunctionCallOutputPayload(): FunctionCallOutputPayload =
    when (this) {
        is StableImageViewResult.Success ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.ContentItems(
                    listOf(
                        FunctionCallOutputContentItem.InputImage(
                            imageUrl = output.imageUrl,
                            detail = when (output.detail) {
                                ViewImageDetail.High -> ImageDetail.High
                                ViewImageDetail.Original -> ImageDetail.Original
                            },
                        ),
                    ),
                ),
                success = true,
            )

        is StableImageViewResult.Failure ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(message),
                success = false,
            )
    }
