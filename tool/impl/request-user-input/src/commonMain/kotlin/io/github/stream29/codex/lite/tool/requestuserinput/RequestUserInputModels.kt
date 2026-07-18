package io.github.stream29.codex.lite.tool.requestuserinput

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Arguments emitted by the model for the `request_user_input` tool. */
@Serializable
public data class RequestUserInputArgs(
    public val questions: List<RequestUserInputQuestion>,
    /**
     * Nullable because a question may be non-blocking or require an explicit
     * answer; `null` means the runtime must wait for the user rather than
     * resolve the call automatically.
     */
    @SerialName("autoResolutionMs")
    public val autoResolutionMs: Long? = null,
)

/** One multiple-choice question rendered by the host runtime. */
@Serializable
public data class RequestUserInputQuestion(
    public val id: String,
    public val header: String,
    public val question: String,
    public val options: List<RequestUserInputQuestionOption>,
)

/** One model-provided answer choice for [RequestUserInputQuestion]. */
@Serializable
public data class RequestUserInputQuestionOption(
    public val label: String,
    public val description: String,
)
