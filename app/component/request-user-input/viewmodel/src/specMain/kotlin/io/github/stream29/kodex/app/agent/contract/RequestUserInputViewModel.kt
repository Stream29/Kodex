package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One draft answer for a pending `ask_user` question. */
public sealed interface RequestUserInputDraftAnswer {
    /**
     * A model-provided mutually exclusive option.
     * @throws IllegalArgumentException if label is blank.
     */
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

/** Submission phase for one ask-user answer draft. */
public sealed interface RequestUserInputSubmissionState {
    /** Render editable questions and enable Submit only when every answer is valid. */
    public data object Editing : RequestUserInputSubmissionState

    /** Render the frozen draft with editing and repeat submission disabled. */
    public data object Submitting : RequestUserInputSubmissionState

    /**
     * Render the message and retained draft; editing clears the failure, retry is explicit.
     * @throws IllegalArgumentException if message is blank.
     */
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

/** Atomic ask-user interaction state for one Agent, projected from `AskUserSnapshot`. */
public sealed interface RequestUserInputState {
    /** This Agent has no unanswered `AskUserEntry` (`answer == null`). */
    public data object Idle : RequestUserInputState

    /**
     * One exact unanswered `AskUserEntry` and all of its answer drafts; render options plus Other,
     * or direct text for option-less questions. A text draft must be nonblank to enable Submit.
     * @throws IllegalArgumentException for negative askId, negative revision or unknown answer keys.
     */
    public data class Pending(
        public val askId: Int,
        public val arguments: RequestUserInputArgs,
        public val answers: Map<String, RequestUserInputDraftAnswer> = emptyMap(),
        public val revision: Long = 0,
        public val submission: RequestUserInputSubmissionState =
            RequestUserInputSubmissionState.Editing,
    ) : RequestUserInputState {
        init {
            require(askId >= 0) { "An ask-user id must not be negative." }
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

/**
 * Answer-draft owner for one Agent's live `AskUserSnapshot` interaction.
 *
 * Every edit includes an explicit [askId] so a delayed frontend event cannot modify a
 * replacement ask entry. Commands and pending projection are confined to the owner's
 * interaction dispatcher. Render Idle without a panel and Pending in question order;
 * keep focus, scrolling and input buffers in the renderer, not another answer authority.
 *
 * New [askId]s reset drafts; repeated projections of the same [askId] are ignored,
 * even if their arguments changed. The host projects the earliest unanswered `AskUserEntry`
 * (`answer == null`) in real time, even while the Agent continues executing shell commands.
 * Hiding a panel or switching tabs must not close this Agent-owned child.
 */
public interface RequestUserInputViewModel : AutoCloseable {
    /** Atomic draft snapshot; never a writable backend pending projection. */
    public val state: StateFlow<RequestUserInputState>

    /**
     * Selects a declared option and increments revision, even for a repeated selection.
     * False for closed/stale/submitting entries, absent question or undeclared label.
     * Clears Failed on an admitted edit.
     * @throws IllegalStateException if an admitted edit exhausts the revision counter.
     * @throws IllegalArgumentException if the pending arguments declare a blank option label.
     */
    public fun selectOption(
        askId: Int,
        questionId: String,
        label: String,
    ): Boolean

    /**
     * Sets an empty free-form draft for a question allowing Other, and increments revision.
     * False for closed/stale/submitting entries, absent question or disallowed Other.
     * Clears Failed; option-less questions instead accept direct [updateFreeForm].
     * @throws IllegalStateException if an admitted edit exhausts the revision counter.
     */
    public fun selectOther(
        askId: Int,
        questionId: String,
    ): Boolean

    /**
     * Updates raw text; with options, Other must already be selected. False for
     * closed/stale/submitting entries or absent/ineligible question. Unchanged text in
     * Editing returns true without advancing revision; other admitted edits clear Failed.
     * @throws IllegalStateException if an admitted change exhausts the revision counter.
     */
    public fun updateFreeForm(
        askId: Int,
        questionId: String,
        text: String,
    ): Boolean

    /**
     * Checks [askId], revision, active submission, and completeness before freezing an answer snapshot.
     * Executes [RequestUserInputDependencies.submitAnswer] in the supplied owner scope.
     * Free-form text is trimmed and prefixed with `user_note: `; options pass unchanged.
     * No retry or rollback is automatic.
     *
     * Cancelling the caller only stops awaiting accepted work. Owner/dependency cancellation
     * propagates and retains Submitting until projection replacement or close. Ordinary
     * failure retains the exact draft as Failed if still current; late success/failure
     * cannot replace a newer state's snapshot.
     *
     * @throws CancellationException if the caller, owner or dependency is cancelled.
     * @throws IllegalStateException if an admitted transition exhausts revisions.
     * @throws IllegalArgumentException if a dependency supplies a blank failure message
     * that cannot be represented by Failed.
     */
    public suspend fun submit(
        askId: Int,
        expectedRevision: Long,
    ): Unit

    /**
     * Idempotently rejects edits, publishes Idle and releases the owned pending observer.
     * Does not cancel accepted submissions, the owner scope, Agent or backend pending.
     */
    override fun close(): Unit
}

/**
 * Capabilities bound to one Agent; no repository, runtime or service locator is exposed.
 * Observation neither authorizes execution nor resumes the Agent.
 */
public interface RequestUserInputDependencies {
    /** Earliest unanswered `AskUserEntry` (`answer == null`) from `AskUserSnapshot`; same-askId updates are deduplicated. */
    public val pending: Flow<AskUserEntry?>

    /**
     * Submits the user's answer for [askId] via `AgentRuntimeRpc.submitAskUserAnswer`, which
     * atomically updates `AskUserSnapshot`, queues `StableAskUserEvent` into `SteerSnapshot`,
     * and wakes/steers the Agent.
     * @throws CancellationException if the bound wait/operation is cancelled.
     * @throws Exception if submission fails.
     */
    public suspend fun submitAnswer(askId: Int, response: RequestUserInputResponse): Unit
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
