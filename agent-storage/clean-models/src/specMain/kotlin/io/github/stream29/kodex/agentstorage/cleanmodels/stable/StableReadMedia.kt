package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.ImageDetail
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed `read_media` interaction.
 *
 * @property arguments Tool-native media-read arguments.
 * @property result Media inspection outcome.
 */
@Serializable
@SerialName("read_media_tool_event")
public data class StableReadMedia(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val arguments: ReadMediaToolArguments,
    public val result: StableReadMediaResult,
) : WorkCommit, AgentCommit.CompletedTool {
    public fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "read_media",
                serializer = ReadMediaToolArguments.serializer(),
                arguments = arguments,
            ),
            stableFunctionOutput(
                callId = callId,
                output = result.toFunctionCallOutputPayload(),
            ),
        )
}

/** Completed outcome of a `read_media` call. */
@Serializable
public sealed interface StableReadMediaResult {
    /** Image bytes were loaded and exposed through [output]. */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val output: ReadMediaToolOutput,
    ) : StableReadMediaResult

    /** The media file could not be loaded. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableReadMediaResult
}

private fun StableReadMediaResult.toFunctionCallOutputPayload(): FunctionCallOutputPayload =
    when (this) {
        is StableReadMediaResult.Success ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.ContentItems(
                    listOf(
                        FunctionCallOutputContentItem.InputImage(
                            imageUrl = output.imageUrl,
                            detail = when (output.detail) {
                                MediaDetail.High -> ImageDetail.High
                                MediaDetail.Original -> ImageDetail.Original
                            },
                        ),
                    ),
                ),
                success = true,
            )

        is StableReadMediaResult.Failure ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(message),
                success = false,
            )
    }
