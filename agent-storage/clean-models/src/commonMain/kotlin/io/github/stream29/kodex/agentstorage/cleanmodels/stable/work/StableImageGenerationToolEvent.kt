package io.github.stream29.kodex.agentstorage.cleanmodels.stable.work

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableFunctionCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableFunctionOutput
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ImageDetail
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.imagegeneration.GeneratedImageOutput
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable completed local `imagegen` interaction.
 *
 * Hosted image generation uses [StableImageGenerationCall].
 */
@Serializable
@SerialName("image_generation_tool_event")
public data class StableImageGenerationToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val arguments: ImageGenToolArguments,
    public val result: StableImageGenerationResult,
) : StableWorkEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "imagegen",
                namespace = "image_gen",
                serializer = ImageGenToolArguments.serializer(),
                arguments = arguments,
            ),
            stableFunctionOutput(
                callId = callId,
                output = result.toFunctionCallOutputPayload(),
            ),
        )
}

/** Completed outcome of local image generation. */
@Serializable
public sealed interface StableImageGenerationResult {
    @Serializable
    @SerialName("success")
    public data class Success(
        public val output: GeneratedImageOutput,
        @SerialName("saved_path")
        public val savedPath: String? = null,
    ) : StableImageGenerationResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableImageGenerationResult
}

private fun StableImageGenerationResult.toFunctionCallOutputPayload(): FunctionCallOutputPayload =
    when (this) {
        is StableImageGenerationResult.Success ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.ContentItems(
                    buildList {
                        add(
                            FunctionCallOutputContentItem.InputImage(
                                imageUrl = "data:image/png;base64,${output.result}",
                                detail = ImageDetail.High,
                            ),
                        )
                        output.outputHint?.let { hint ->
                            add(FunctionCallOutputContentItem.InputText(hint))
                        }
                    },
                ),
                success = true,
            )

        is StableImageGenerationResult.Failure ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(message),
                success = false,
            )
    }
