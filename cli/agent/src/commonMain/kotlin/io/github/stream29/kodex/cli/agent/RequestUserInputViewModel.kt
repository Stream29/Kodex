package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One draft answer for a pending `request_user_input` question. */
public sealed interface RequestUserInputDraftAnswer {
    /** A model-provided mutually exclusive option. */
    public data class Option(
        public val label: String,
    ) : RequestUserInputDraftAnswer

    /** A host-provided free-form answer, including the automatically added Other choice. */
    public data class FreeForm(
        public val text: String,
    ) : RequestUserInputDraftAnswer
}

/** Frontend state for one pending host-owned `request_user_input` call. */
public data class RequestUserInputViewState(
    public val callId: String? = null,
    public val arguments: RequestUserInputArgs? = null,
    public val answers: Map<String, RequestUserInputDraftAnswer> = emptyMap(),
    public val isSubmitting: Boolean = false,
    public val failureMessage: String? = null,
) {
    /** Whether every question has a valid nonblank response ready to submit. */
    public val canSubmit: Boolean
        get() = !isSubmitting && arguments?.questions?.all { question ->
            answers[question.id].isValidFor(question)
        } == true
}

/**
 * Holds answer drafts for one Agent's pending `request_user_input` call.
 *
 * The pending call itself remains owned by AgentState. This model only keeps UI-local selections
 * until [AgentRuntimeViewModel] atomically completes that call.
 */
public class RequestUserInputViewModel {
    private var pending: PendingRequestUserInputToolEvent? = null
    private val mutableState = MutableStateFlow(RequestUserInputViewState())

    public val state: StateFlow<RequestUserInputViewState> = mutableState.asStateFlow()

    /** Selects a declared option for [questionId]. Invalid or stale selections are ignored. */
    public fun selectOption(
        questionId: String,
        label: String,
    ) {
        val question = state.value.arguments?.questions?.singleOrNull { it.id == questionId } ?: return
        if (question.options.orEmpty().none { option -> option.label == label }) return
        mutableState.update { current ->
            current.copy(
                answers = current.answers + (questionId to RequestUserInputDraftAnswer.Option(label)),
                failureMessage = null,
            )
        }
    }

    /** Selects the client-provided free-form Other answer for [questionId]. */
    public fun selectOther(questionId: String) {
        val question = state.value.arguments?.questions?.singleOrNull { it.id == questionId } ?: return
        if (!question.allowsOtherAnswer) return
        mutableState.update { current ->
            current.copy(
                answers = current.answers + (questionId to RequestUserInputDraftAnswer.FreeForm("")),
                failureMessage = null,
            )
        }
    }

    /** Updates a free-form answer for an option-less question or a selected Other answer. */
    public fun updateFreeForm(
        questionId: String,
        text: String,
    ) {
        val question = state.value.arguments?.questions?.singleOrNull { it.id == questionId } ?: return
        val currentAnswer = state.value.answers[questionId]
        if (question.options.orEmpty().isNotEmpty() && currentAnswer !is RequestUserInputDraftAnswer.FreeForm) {
            return
        }
        mutableState.update { current ->
            current.copy(
                answers = current.answers + (questionId to RequestUserInputDraftAnswer.FreeForm(text)),
                failureMessage = null,
            )
        }
    }

    internal fun synchronize(pending: PendingRequestUserInputToolEvent?) {
        if (this.pending?.callId == pending?.callId) return
        this.pending = pending
        mutableState.value = pending?.let { call ->
            RequestUserInputViewState(
                callId = call.callId,
                arguments = call.arguments,
            )
        } ?: RequestUserInputViewState()
    }

    internal fun beginSubmission(): RequestUserInputSubmission? {
        val call = pending ?: return null
        val current = state.value
        if (current.callId != call.callId || !current.canSubmit) return null
        val response = current.arguments?.toResponse(current.answers) ?: return null
        mutableState.value = current.copy(isSubmitting = true, failureMessage = null)
        return RequestUserInputSubmission(call, response)
    }

    internal fun completeSubmission(callId: String) {
        if (pending?.callId != callId) return
        pending = null
        mutableState.value = RequestUserInputViewState()
    }

    internal fun failSubmission(
        callId: String,
        failure: Throwable,
    ) {
        if (pending?.callId != callId) return
        mutableState.update { current ->
            current.copy(
                isSubmitting = false,
                failureMessage = failure.message ?: failure.toString(),
            )
        }
    }
}

internal data class RequestUserInputSubmission(
    val pending: PendingRequestUserInputToolEvent,
    val response: RequestUserInputResponse,
)

/**
 * The model-facing schema promises that the client adds Other. Protocol-originated requests can
 * still set [RequestUserInputQuestion.isOther] explicitly, so both shapes are accepted here.
 */
public val RequestUserInputQuestion.allowsOtherAnswer: Boolean
    get() = isOther || options.orEmpty().isNotEmpty()

private fun RequestUserInputDraftAnswer?.isValidFor(question: RequestUserInputQuestion): Boolean = when (this) {
    is RequestUserInputDraftAnswer.Option -> question.options.orEmpty().any { option -> option.label == label }
    is RequestUserInputDraftAnswer.FreeForm -> text.trim().isNotEmpty()
    null -> false
}

private fun RequestUserInputArgs.toResponse(
    drafts: Map<String, RequestUserInputDraftAnswer>,
): RequestUserInputResponse = RequestUserInputResponse(
    answers = questions.associate { question ->
        val answer = requireNotNull(drafts[question.id]) {
            "Question ${question.id} has no answer draft."
        }
        question.id to RequestUserInputAnswer(
            answers = when (answer) {
                is RequestUserInputDraftAnswer.Option -> listOf(answer.label)
                is RequestUserInputDraftAnswer.FreeForm -> listOf("user_note: ${answer.text.trim()}")
            },
        )
    },
)
