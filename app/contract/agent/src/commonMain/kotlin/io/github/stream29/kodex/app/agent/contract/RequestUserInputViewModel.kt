package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.coroutines.flow.StateFlow

/** One draft answer for a pending `request_user_input` question. */
public sealed interface RequestUserInputDraftAnswer {
    /** A model-provided mutually exclusive option. */
    public data class Option(
        public val label: String,
    ) : RequestUserInputDraftAnswer {
        init {
            require(label.isNotBlank()) { "A request-user-input option label must not be blank." }
        }
    }

    /** A host-provided free-form answer, including the added Other choice. */
    public data class FreeForm(
        public val text: String,
    ) : RequestUserInputDraftAnswer
}

/** Submission phase for one request-user-input answer draft. */
public sealed interface RequestUserInputSubmissionState {
    public data object Editing : RequestUserInputSubmissionState

    public data object Submitting : RequestUserInputSubmissionState

    public data class Failed(
        public val message: String,
    ) : RequestUserInputSubmissionState {
        init {
            require(message.isNotBlank()) {
                "A request-user-input failure message must not be blank."
            }
        }
    }
}

/** Atomic blocking-interaction state for one Agent. */
public sealed interface RequestUserInputState {
    /** This Agent has no pending request-user-input call. */
    public data object Idle : RequestUserInputState

    /** One exact call and all of its answer drafts. */
    public data class Pending(
        public val callId: String,
        public val arguments: RequestUserInputArgs,
        public val answers: Map<String, RequestUserInputDraftAnswer> = emptyMap(),
        public val revision: Long = 0,
        public val submission: RequestUserInputSubmissionState =
            RequestUserInputSubmissionState.Editing,
    ) : RequestUserInputState {
        init {
            require(callId.isNotBlank()) { "A request-user-input call id must not be blank." }
            require(revision >= 0) { "A request-user-input revision must not be negative." }
            val questionIds = arguments.questions.mapTo(mutableSetOf(), RequestUserInputQuestion::id)
            require(answers.keys.all { questionId -> questionId in questionIds }) {
                "Every request-user-input answer must belong to a current question."
            }
        }

        /** Whether every question has a valid answer and no submission is active. */
        public val canSubmit: Boolean
            get() = submission !is RequestUserInputSubmissionState.Submitting &&
                arguments.questions.all { question ->
                    answers[question.id].isValidFor(question)
                }
    }
}

/** Result of a revision-bound request-user-input submission command. */
public sealed interface RequestUserInputSubmissionResult {
    public data object Submitted : RequestUserInputSubmissionResult
    public data object StaleCall : RequestUserInputSubmissionResult
    public data object StaleRevision : RequestUserInputSubmissionResult
    public data object Incomplete : RequestUserInputSubmissionResult
    public data object Busy : RequestUserInputSubmissionResult

    public data class Failed(
        public val message: String,
    ) : RequestUserInputSubmissionResult {
        init {
            require(message.isNotBlank()) {
                "A request-user-input submission failure message must not be blank."
            }
        }
    }
}

/**
 * Answer-draft owner for one Agent's blocking request-user-input interaction.
 *
 * Every edit includes an explicit call id so a delayed frontend event cannot modify a
 * replacement call.
 */
public interface RequestUserInputViewModel : AutoCloseable {
    public val state: StateFlow<RequestUserInputState>

    public fun selectOption(
        callId: String,
        questionId: String,
        label: String,
    ): Boolean

    public fun selectOther(
        callId: String,
        questionId: String,
    ): Boolean

    public fun updateFreeForm(
        callId: String,
        questionId: String,
        text: String,
    ): Boolean

    /**
     * Completes the exact call/revision and resumes its owning Agent when valid.
     */
    public suspend fun submit(
        callId: String,
        expectedRevision: Long,
    ): RequestUserInputSubmissionResult

    override fun close(): Unit
}

/** Whether the host must offer a free-form answer for this question. */
public val RequestUserInputQuestion.allowsOtherAnswer: Boolean
    get() = isOther || options.orEmpty().isNotEmpty()

private fun RequestUserInputDraftAnswer?.isValidFor(
    question: RequestUserInputQuestion,
): Boolean = when (this) {
    is RequestUserInputDraftAnswer.Option ->
        question.options.orEmpty().any { option -> option.label == label }

    is RequestUserInputDraftAnswer.FreeForm -> text.trim().isNotEmpty()
    null -> false
}
