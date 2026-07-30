package io.github.stream29.kodex.tool.requestuserinput

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

/** One multiple-choice question rendered by the host UI. */
@Serializable
public data class RequestUserInputQuestion(
    public val id: String,
    public val header: String,
    public val question: String,
    @SerialName("isOther")
    public val isOther: Boolean = false,
    @SerialName("isSecret")
    public val isSecret: Boolean = false,
    /**
     * Nullable because protocol-originated elicitation may ask for free-form
     * text; `null` means the host must render a text answer without choices.
     */
    public val options: List<RequestUserInputQuestionOption>? = null,
)

/** One model-provided answer choice for [RequestUserInputQuestion]. */
@Serializable
public data class RequestUserInputQuestionOption(
    public val label: String,
    public val description: String,
)

/** Answers returned to the model, keyed by [RequestUserInputQuestion.id]. */
@Serializable
public data class RequestUserInputResponse(
    public val answers: Map<String, RequestUserInputAnswer>,
)

/** One question's selected or free-form values. */
@Serializable
public data class RequestUserInputAnswer(
    public val answers: List<String>,
)
