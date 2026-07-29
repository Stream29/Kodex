package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

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
    public val questions: List<StableRequestUserInputQuestion>,
    @SerialName("auto_resolution_ms")
    public val autoResolutionMillis: Long? = null,
    public val result: StableRequestUserInputResult,
) : StableToolEvent

/** One user-input question shown by the host. */
@Serializable
public data class StableRequestUserInputQuestion(
    public val id: String,
    public val header: String,
    public val question: String,
    @SerialName("allows_other")
    public val allowsOther: Boolean = false,
    @SerialName("is_secret")
    public val isSecret: Boolean = false,
    public val options: List<StableRequestUserInputOption>? = null,
)

/** One selectable answer presented for a user-input question. */
@Serializable
public data class StableRequestUserInputOption(
    public val label: String,
    public val description: String,
)

/** Completed result of a user-input request. */
@Serializable
public sealed interface StableRequestUserInputResult {
    /** User or auto-resolution answers keyed by question id. */
    @Serializable
    @SerialName("answered")
    public data class Answered(
        public val answers: Map<String, StableRequestUserInputAnswer>,
    ) : StableRequestUserInputResult

    /** The request failed without answers. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableRequestUserInputResult
}

/** Selected or free-form values for one question. */
@Serializable
public data class StableRequestUserInputAnswer(
    public val values: List<String>,
)
