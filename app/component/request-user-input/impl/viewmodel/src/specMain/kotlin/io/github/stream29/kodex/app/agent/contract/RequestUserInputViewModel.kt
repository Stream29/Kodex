package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One draft answer for a pending `request_user_input` question. */
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

/** Submission phase for one request-user-input answer draft. */
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

/** Atomic blocking-interaction state for one Agent. */
public sealed interface RequestUserInputState {
    /** This Agent has no pending request-user-input call. */
    public data object Idle : RequestUserInputState

    /**
     * One exact call and all of its answer drafts; render options plus Other, or direct text
     * for option-less questions. A text draft must be nonblank to enable Submit.
     * @throws IllegalArgumentException for blank call id, negative revision or unknown answer keys.
     */
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
    /** Completion returned and the bound resume callback returned; not a rollback guarantee. */
    public data object Submitted : RequestUserInputSubmissionResult
    /** Closed, idle, or no longer the exact pending call. */
    public data object StaleCall : RequestUserInputSubmissionResult
    /** Exact call, but revision changed, including losing the submission CAS. */
    public data object StaleRevision : RequestUserInputSubmissionResult
    /** At least one answer is absent, blank, or not a declared option. */
    public data object Incomplete : RequestUserInputSubmissionResult
    /** Exact current revision is already submitting. */
    public data object Busy : RequestUserInputSubmissionResult

    /**
     * A non-cancellation completion/resume failure; some side effects may have happened.
     * @throws IllegalArgumentException if message is blank.
     */
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
 * replacement call. Commands and pending projection are confined to the owner's
 * interaction dispatcher. Render Idle without a panel and Pending in question order;
 * keep focus, scrolling and input buffers in the renderer, not another answer authority.
 *
 * New call ids reset drafts; repeated projections of the same call id are ignored,
 * even if their arguments changed. The host keeps its existing first-pending projection.
 * Hiding a panel or switching tabs must not close this Agent-owned child.
 */
public interface RequestUserInputViewModel : AutoCloseable {
    /** Atomic draft snapshot; never a writable backend pending projection. */
    public val state: StateFlow<RequestUserInputState>

    /**
     * Selects a declared option and increments revision, even for a repeated selection.
     * False for closed/stale/submitting calls, absent question or undeclared label.
     * Clears Failed on an admitted edit.
     * @throws IllegalStateException if an admitted edit exhausts the revision counter.
     * @throws IllegalArgumentException if the pending arguments declare a blank option label.
     */
    public fun selectOption(
        callId: String,
        questionId: String,
        label: String,
    ): Boolean

    /**
     * Sets an empty free-form draft for a question allowing Other, and increments revision.
     * False for closed/stale/submitting calls, absent question or disallowed Other.
     * Clears Failed; option-less questions instead accept direct [updateFreeForm].
     * @throws IllegalStateException if an admitted edit exhausts the revision counter.
     */
    public fun selectOther(
        callId: String,
        questionId: String,
    ): Boolean

    /**
     * Updates raw text; with options, Other must already be selected. False for
     * closed/stale/submitting calls or absent/ineligible question. Unchanged text in
     * Editing returns true without advancing revision; other admitted edits clear Failed.
     * @throws IllegalStateException if an admitted change exhausts the revision counter.
     */
    public fun updateFreeForm(
        callId: String,
        questionId: String,
        text: String,
    ): Boolean

    /**
     * Checks call, revision, Busy, then completeness, before freezing an answer snapshot.
     * Executes completion then the bound resume callback in the supplied owner scope.
     * Free-form text is trimmed and prefixed with `user_note: `; options pass unchanged.
     * Completion's index is intentionally unused. No retry or rollback is automatic.
     *
     * Cancelling the caller only stops awaiting accepted work. Owner/dependency cancellation
     * propagates and retains Submitting until projection replacement or close. Ordinary
     * failure retains the exact draft as Failed if still current; late success/failure
     * cannot replace a newer state's snapshot. Success still clears the internal pending
     * event and calls resume, even after replacement/close, preserving existing behavior.
     *
     * @throws CancellationException if the caller, owner or dependency is cancelled.
     * @throws IllegalStateException if an admitted transition exhausts revisions.
     * @throws IllegalArgumentException if a dependency supplies a blank failure message
     * that cannot be represented by Failed.
     */
    public suspend fun submit(
        callId: String,
        expectedRevision: Long,
    ): RequestUserInputSubmissionResult

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
    /** Existing first-pending projection; same-call updates are intentionally deduplicated. */
    public val pending: Flow<PendingRequestUserInputToolEvent?>

    /**
     * Completes the original tool event and returns the existing committed storage index.
     * Does not implicitly resume; a lost/failed return does not prove nothing was committed.
     * @throws CancellationException if the bound wait/operation is cancelled.
     * @throws Exception if completion fails.
     */
    public suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int

    /**
     * Requests continuation of the same Agent using the existing host-owned behavior.
     * A normal return need not mean runtime execution has finished.
     * @throws Exception if the host cannot request continuation.
     */
    public fun resumeRuntime(): Unit
}

/** Creates an Agent-owned child; it borrows but never closes [CoroutineScope]. */
public fun interface RequestUserInputViewModelFactory {
    /**
     * Observes pending until close/owner termination/upstream completion; upstream completion
     * closes the child. Accepted submit operations run directly in [ownerScope].
     * Upstream failures follow that scope's existing coroutine failure policy.
     */
    public fun create(
        dependencies: RequestUserInputDependencies,
        ownerScope: CoroutineScope,
    ): RequestUserInputViewModel
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
