package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed `request_user_input` interaction.
 *
 * Pending questions remain unstable state; this event is emitted only after an
 * answer or failure completes the tool call.
 */
@Serializable
@SerialName("request_user_input_tool_event")
public data class StableRequestUserInputToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val arguments: RequestUserInputArgs,
    public val result: StableRequestUserInputResult,
) : StableCleanEvent.CompletedTool, RemoteCompactionV2RetainedItem {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "request_user_input",
                serializer = RequestUserInputArgs.serializer(),
                arguments = arguments,
            ),
            result.toFunctionOutput(callId),
        )
}

/** Completed result of a user-input request. */
@Serializable
public sealed interface StableRequestUserInputResult {
    /** User or auto-resolution answers in the tool-native result model. */
    @Serializable
    @SerialName("answered")
    public data class Answered(
        public val response: RequestUserInputResponse,
    ) : StableRequestUserInputResult

    /** The request failed without answers. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableRequestUserInputResult
}

private fun StableRequestUserInputResult.toFunctionOutput(
    callId: String,
): ResponseItem.FunctionCallOutput =
    when (this) {
        is StableRequestUserInputResult.Answered ->
            stableJsonOutput(
                callId = callId,
                serializer = RequestUserInputResponse.serializer(),
                result = response,
                success = true,
            )

        is StableRequestUserInputResult.Failure ->
            stableTextOutput(
                callId = callId,
                text = message,
                success = false,
            )
    }
