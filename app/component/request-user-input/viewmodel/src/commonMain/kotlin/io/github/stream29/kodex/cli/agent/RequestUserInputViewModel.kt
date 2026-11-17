package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDependencies
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public actual fun createRequestUserInputViewModel(
    dependencies: RequestUserInputDependencies,
    ownerScope: CoroutineScope,
): RequestUserInputViewModel = RequestUserInputViewModelImpl(
    dependencies::submitAnswer,
    ownerScope,
).also { view ->
    view.observation = ownerScope.launch {
        try {
            dependencies.pending.collect(view::synchronize)
        } finally {
            view.close()
        }
    }
}

/** Revision-bound answer drafts for one Agent's live `AskUserSnapshot` interaction. */
internal class RequestUserInputViewModelImpl(
    private val submitAnswer: suspend (Int, RequestUserInputResponse) -> Unit,
    private val ownerScope: CoroutineScope,
) : RequestUserInputViewModel {
    internal var observation: Job? = null
    private var pendingEntry: AskUserEntry? = null
    private val mutableState = MutableStateFlow<RequestUserInputState>(RequestUserInputState.Idle)
    private var closed: Boolean = false

    override val state: StateFlow<RequestUserInputState> = mutableState.asStateFlow()

    override fun selectOption(
        askId: Int,
        questionId: String,
        label: String,
    ): Boolean = edit(askId, questionId) { current, question ->
        if (question.options.orEmpty().none { option -> option.label == label }) return@edit null
        current.copy(
            answers = current.answers + (questionId to RequestUserInputDraftAnswer.Option(label)),
            revision = current.nextRevision(),
            submission = RequestUserInputSubmissionState.Editing,
        )
    }

    override fun selectOther(
        askId: Int,
        questionId: String,
    ): Boolean = edit(askId, questionId) { current, question ->
        if (!question.allowsOtherAnswer) return@edit null
        current.copy(
            answers = current.answers + (questionId to RequestUserInputDraftAnswer.FreeForm("")),
            revision = current.nextRevision(),
            submission = RequestUserInputSubmissionState.Editing,
        )
    }

    override fun updateFreeForm(
        askId: Int,
        questionId: String,
        text: String,
    ): Boolean = edit(askId, questionId) { current, question ->
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
        askId: Int,
        expectedRevision: Long,
    ): Unit = ownerScope.async(
        start = CoroutineStart.UNDISPATCHED,
    ) {
        if (closed) return@async
        val entry = pendingEntry
            ?.takeIf { pending -> pending.askId == askId }
            ?: return@async
        val captured = state.value as? RequestUserInputState.Pending
            ?: return@async
        if (
            captured.askId != askId ||
            captured.revision != expectedRevision ||
            captured.submission is RequestUserInputSubmissionState.Submitting ||
            !captured.canSubmit
        ) {
            return@async
        }
        val response = captured.arguments.toResponse(captured.answers)
        val submitting = captured.copy(
            revision = captured.nextRevision(),
            submission = RequestUserInputSubmissionState.Submitting,
        )
        if (!mutableState.compareAndSet(captured, submitting)) return@async
        try {
            submitAnswer(entry.askId, response)
            if (pendingEntry?.askId == entry.askId) {
                pendingEntry = null
            }
            mutableState.compareAndSet(submitting, RequestUserInputState.Idle)
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
        }
    }.await()

    internal fun synchronize(pending: AskUserEntry?) {
        if (closed || pendingEntry?.askId == pending?.askId) return
        pendingEntry = pending
        mutableState.value = pending?.let { entry ->
            RequestUserInputState.Pending(
                askId = entry.askId,
                arguments = entry.questions,
            )
        } ?: RequestUserInputState.Idle
    }

    override fun close() {
        if (closed) return
        closed = true
        observation?.cancel()
        observation = null
        pendingEntry = null
        mutableState.value = RequestUserInputState.Idle
    }

    private fun edit(
        askId: Int,
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
                current.askId != askId ||
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

internal fun RequestUserInputState.Pending.nextRevision(): Long {
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
