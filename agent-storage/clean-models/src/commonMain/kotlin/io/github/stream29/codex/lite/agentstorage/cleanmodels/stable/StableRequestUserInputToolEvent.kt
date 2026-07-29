package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputResponse
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
    public val arguments: RequestUserInputArgs,
    public val result: StableRequestUserInputResult,
) : StableCleanEvent.CompletedTool

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
