package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionResult
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.agent.contract.allowsOtherAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Revision-bound answer drafts for one Agent's current blocking interaction. */
internal class RequestUserInputViewModelImpl(
    private val runtime: AgentRuntime,
    private val ownerScope: CoroutineScope,
    private val resumeRuntime: () -> Unit,
) : RequestUserInputViewModel {
    private var pendingEvent: PendingRequestUserInputToolEvent? = null
    private val mutableState = MutableStateFlow<RequestUserInputState>(RequestUserInputState.Idle)
    private var closed: Boolean = false

    override val state: StateFlow<RequestUserInputState> = mutableState.asStateFlow()

    override fun selectOption(
        callId: String,
        questionId: String,
        label: String,
    ): Boolean = edit(callId, questionId) { current, question ->
        if (question.options.orEmpty().none { option -> option.label == label }) return@edit null
        current.copy(
            answers = current.answers + (questionId to RequestUserInputDraftAnswer.Option(label)),
            revision = current.nextRevision(),
            submission = RequestUserInputSubmissionState.Editing,
        )
    }

    override fun selectOther(
        callId: String,
        questionId: String,
    ): Boolean = edit(callId, questionId) { current, question ->
        if (!question.allowsOtherAnswer) return@edit null
        current.copy(
            answers = current.answers + (questionId to RequestUserInputDraftAnswer.FreeForm("")),
            revision = current.nextRevision(),
            submission = RequestUserInputSubmissionState.Editing,
        )
    }

    override fun updateFreeForm(
        callId: String,
        questionId: String,
        text: String,
    ): Boolean = edit(callId, questionId) { current, question ->
        val answer = current.answers[questionId]
        if (question.options.orEmpty().isNotEmpty() && answer !is RequestUserInputDraftAnswer.FreeForm) {
            return@edit null
        }
        val updated = RequestUserInputDraftAnswer.FreeForm(text)
        if (answer == updated && current.submission is RequestUserInputSubmissionState.Editing) {
            current
        } else {
            current.copy(
                answers = current.answers + (questionId to updated),
                revision = current.nextRevision(),
                submission = RequestUserInputSubmissionState.Editing,
            )
        }
    }

    override suspend fun submit(
        callId: String,
        expectedRevision: Long,
    ): RequestUserInputSubmissionResult = ownerScope.async(
        start = CoroutineStart.UNDISPATCHED,
    ) {
        if (closed) return@async RequestUserInputSubmissionResult.StaleCall
        val event = pendingEvent
            ?.takeIf { pending -> pending.callId == callId }
            ?: return@async RequestUserInputSubmissionResult.StaleCall
        val captured = state.value as? RequestUserInputState.Pending
            ?: return@async RequestUserInputSubmissionResult.StaleCall
        if (captured.callId != callId) {
            return@async RequestUserInputSubmissionResult.StaleCall
        }
        if (captured.revision != expectedRevision) {
            return@async RequestUserInputSubmissionResult.StaleRevision
        }
        if (captured.submission is RequestUserInputSubmissionState.Submitting) {
            return@async RequestUserInputSubmissionResult.Busy
        }
        if (!captured.canSubmit) {
            return@async RequestUserInputSubmissionResult.Incomplete
        }
        val response = captured.arguments.toResponse(captured.answers)
        val submitting = captured.copy(
            revision = captured.nextRevision(),
            submission = RequestUserInputSubmissionState.Submitting,
        )
        if (!mutableState.compareAndSet(captured, submitting)) {
            val latest = state.value as? RequestUserInputState.Pending
            return@async if (latest?.callId != callId) {
                RequestUserInputSubmissionResult.StaleCall
            } else {
                RequestUserInputSubmissionResult.StaleRevision
            }
        }
        try {
            runtime.completeToolCall(
                StableRequestUserInputToolEvent(
                    callId = event.callId,
                    itemId = event.itemId,
                    arguments = event.arguments,
                    result = StableRequestUserInputResult.Answered(response),
                ),
            )
            pendingEvent = null
            mutableState.compareAndSet(submitting, RequestUserInputState.Idle)
            resumeRuntime()
            RequestUserInputSubmissionResult.Submitted
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            val message = failure.message ?: failure.toString()
            mutableState.compareAndSet(
                submitting,
                submitting.copy(
                    revision = submitting.nextRevision(),
                    submission = RequestUserInputSubmissionState.Failed(message),
                ),
            )
            RequestUserInputSubmissionResult.Failed(message)
        }
    }.await()

    internal fun synchronize(pending: PendingRequestUserInputToolEvent?) {
        if (closed || pendingEvent?.callId == pending?.callId) return
        pendingEvent = pending
        mutableState.value = pending?.let { call ->
            RequestUserInputState.Pending(
                callId = call.callId,
                arguments = call.arguments,
            )
        } ?: RequestUserInputState.Idle
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingEvent = null
        mutableState.value = RequestUserInputState.Idle
    }

    private fun edit(
        callId: String,
        questionId: String,
        transform: (
            RequestUserInputState.Pending,
            RequestUserInputQuestion,
        ) -> RequestUserInputState.Pending?,
    ): Boolean {
        if (closed) return false
        while (true) {
            val current = state.value as? RequestUserInputState.Pending ?: return false
            if (
                current.callId != callId ||
                current.submission is RequestUserInputSubmissionState.Submitting
            ) {
                return false
            }
            val question = current.arguments.questions.singleOrNull { it.id == questionId }
                ?: return false
            val updated = transform(current, question) ?: return false
            if (updated === current || mutableState.compareAndSet(current, updated)) return true
        }
    }
}

private fun RequestUserInputState.Pending.nextRevision(): Long {
    check(revision < Long.MAX_VALUE) { "Request-user-input revisions are exhausted." }
    return revision + 1
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
