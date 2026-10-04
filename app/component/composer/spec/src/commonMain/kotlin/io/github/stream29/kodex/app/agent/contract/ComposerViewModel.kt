package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.RequestUserInputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Exact Agent or New Session address bound to one Composer child. */
@JvmInline
public value class ComposerOwnerId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A Composer owner identity must not be blank." }
    }
}

/** The business operation whose failure is being presented or reported. */
public enum class ComposerOperation {
    Submit,
    Steer,
    Resume,
    Cancel,
}

/** A framework-free failure summary retained by the component. */
public data class ComposerFailure(
    public val operation: ComposerOperation,
    public val message: String,
) {
    init {
        require(message.isNotBlank()) { "A Composer failure message must not be blank." }
    }
}

/** Submission lifecycle of the current draft; the renderer owns no copy of this state. */
public sealed interface ComposerSubmissionState {
    /** The draft can be edited and an explicit command may be admitted. */
    public data object Editing : ComposerSubmissionState

    /** One admitted command is awaiting its caller-owned dependency operation. */
    public data object Submitting : ComposerSubmissionState

    /**
     * The last dependency operation failed and the exact draft is retained for an explicit edit.
     * Editing the draft returns to [Editing]; no automatic retry is implied.
     */
    public data class Failed(
        public val failure: ComposerFailure,
    ) : ComposerSubmissionState
}

/** The only request-user-input information a Composer may project. */
public sealed interface ComposerRequestInputPresentation {
    /** No sibling request-user-input interaction is currently presented. */
    public data object None : ComposerRequestInputPresentation

    /**
     * A sibling child owns the pending answers and lifecycle.
     *
     * [callId], [mode], title and question count are presentation facts only. The Composer does
     * not expose, copy or mutate answer drafts.
     *
     * @throws IllegalArgumentException if [callId] or [title] is blank, or [questionCount] is negative.
     */
    public data class Pending(
        public val callId: String,
        public val mode: RequestUserInputMode,
        public val title: String,
        public val questionCount: Int,
    ) : ComposerRequestInputPresentation {
        init {
            require(callId.isNotBlank()) { "A request-user-input call id must not be blank." }
            require(title.isNotBlank()) { "A request-user-input title must not be blank." }
            require(questionCount >= 0) {
                "A request-user-input question count must not be negative."
            }
        }
    }
}

/** Renderer branch derived from business state and sibling presentation. */
public enum class ComposerMode {
    Empty,
    Ready,
    Running,
    Steer,
    RequestInput,
    Error,
    Closed,
}

/** Lifecycle of the component, independent from the backend Agent lifecycle. */
public enum class ComposerLifecycle {
    Open,
    Closed,
}

/**
 * Complete framework-free Composer projection.
 *
 * [text], [cursorOffset] and [revision] are the component's editable projection. Selection,
 * focus, widget buffer, scroll viewport and layout remain renderer-local.
 * [pendingSteer] is a backend projection, not a second queue: the component only admits one
 * exact steer command through [ComposerSteerPort].
 *
 * @throws IllegalArgumentException if [revision] is negative or [ownerId] is invalid.
 */
public data class ComposerState(
    public val ownerId: ComposerOwnerId,
    public val text: String = "",
    public val revision: Long = 0,
    /** The current insertion point retained for hosts that restore the editable projection. */
    public val cursorOffset: Int = text.length,
    public val running: Boolean = false,
    public val pendingSteer: List<StableIndexEvent.Steerable> = emptyList(),
    public val requestInput: ComposerRequestInputPresentation =
        ComposerRequestInputPresentation.None,
    public val submission: ComposerSubmissionState = ComposerSubmissionState.Editing,
    public val lifecycle: ComposerLifecycle = ComposerLifecycle.Open,
) {
    init {
        require(revision >= 0) { "A Composer revision must not be negative." }
        require(cursorOffset in 0..text.length) {
            "Composer cursor offset must be within the draft."
        }
    }

    /** Selects the renderer branch without introducing renderer state into the component. */
    public val mode: ComposerMode
        get() = when {
            lifecycle == ComposerLifecycle.Closed -> ComposerMode.Closed
            submission is ComposerSubmissionState.Failed -> ComposerMode.Error
            requestInput is ComposerRequestInputPresentation.Pending ->
                ComposerMode.RequestInput
            pendingSteer.isNotEmpty() -> ComposerMode.Steer
            running -> ComposerMode.Running
            text.isBlank() -> ComposerMode.Empty
            else -> ComposerMode.Ready
        }

}

/** Result returned after exact revision admission and one explicit dependency command. */
public sealed interface ComposerSubmissionResult {
    /**
     * The user message was persisted by [ComposerSubmitPort] and the exact owner was asked to
     * resume. A resume return does not prove that execution has completed.
     */
    public data object Submitted : ComposerSubmissionResult

    /**
     * The exact steer was accepted into the owner runtime projection. Acceptance is not a
     * persistence receipt and does not create a component-global queue.
     */
    public data object QueuedAsSteer : ComposerSubmissionResult

    /** The admitted draft contained only blank/whitespace text. */
    public data object Empty : ComposerSubmissionResult

    /** The caller supplied a revision that is no longer the component's exact revision. */
    public data object Stale : ComposerSubmissionResult

    /** The component is closed or no longer available for a command. */
    public data object Unavailable : ComposerSubmissionResult

    /**
     * A non-cancellation dependency failure; side effects may already have happened.
     *
     * @throws IllegalArgumentException if [message] is blank.
     */
    public data class Failed(
        public val message: String,
    ) : ComposerSubmissionResult {
        init {
            require(message.isNotBlank()) { "A Composer failure message must not be blank." }
        }
    }
}

/**
 * Narrow port for the non-running submit command.
 *
 * Implementations bind [ownerId] to the exact Agent/New Session instance. They must not perform
 * a current-owner lookup, retry, compensation or backend rollback.
 * Normal return means the append really completed; an unavailable draft-only owner must reject
 * the call instead of reporting success without persisting anything.
 *
 * @throws CancellationException when the caller or owner cancels the operation.
 * @throws Exception when persistence fails; the component reports the failure without claiming
 * that no append occurred.
 */
public fun interface ComposerSubmitPort {
    public suspend fun submit(
        ownerId: ComposerOwnerId,
        content: List<ContentItem>,
    ): Unit
}

/**
 * Narrow port for the running-Agent steer command.
 *
 * Normal return means the exact runtime accepted the pending message, not that it was persisted.
 * Rejection/failure must throw; there is no single-case receipt wrapper to manufacture success.
 *
 * @throws CancellationException when the caller or owner cancels the operation.
 * @throws Exception when the exact owner rejects or fails the command.
 */
public fun interface ComposerSteerPort {
    public suspend fun steer(
        ownerId: ComposerOwnerId,
        content: List<ContentItem>,
    ): Unit
}

/**
 * Explicit cancellation port for the exact current owner.
 *
 * Calling [ComposerViewModel.cancel] is distinct from cancelling the caller waiting for submit;
 * caller cancellation is propagated and never translated into this command.
 *
 * @throws CancellationException when the owner cancellation is cancelled.
 * @throws Exception when the exact owner reports a cancellation failure.
 */
public fun interface ComposerCancellationPort {
    public fun cancel(ownerId: ComposerOwnerId): Unit
}

/**
 * Resume port used only after a normal submit has persisted its user message.
 *
 * It receives the captured owner identity even if the child was closed or replaced while the
 * accepted operation completed; it never resolves a different current owner.
 *
 * @throws CancellationException when the exact owner rejects the cancellation context.
 * @throws Exception when resuming the exact owner fails.
 */
public fun interface ComposerResumePort {
    public fun resume(ownerId: ComposerOwnerId): Unit
}

/** Backend observations used to choose Running/Steer branches; no command authority is exposed. */
public interface ComposerRuntimePort {
    public val running: StateFlow<Boolean>
    public val pendingSteer: StateFlow<List<StableIndexEvent.Steerable>>
}

/**
 * Presentation-only sibling port for request-user-input.
 *
 * It must be adapted from the sibling child; exposing this port does not authorize completion,
 * answer editing, resume, or lifecycle changes.
 */
public interface ComposerRequestInputPort {
    public val presentation: StateFlow<ComposerRequestInputPresentation>
}

/**
 * Typed failure sink for the exact owner operation.
 *
 * @throws Exception if the host cannot record the failure.
 */
public fun interface ComposerFailureReporter {
    public fun report(
        ownerId: ComposerOwnerId,
        failure: ComposerFailure,
    ): Unit
}

/** Narrow, fully typed dependencies required by one Composer child. */
public interface ComposerDependencies {
    public val runtime: ComposerRuntimePort
    public val submit: ComposerSubmitPort
    public val steer: ComposerSteerPort
    public val cancellation: ComposerCancellationPort
    public val resume: ComposerResumePort
    public val requestInput: ComposerRequestInputPort
    public val failures: ComposerFailureReporter
}

/**
 * Owns one fixed owner's draft, exact revision admission and command ordering.
 *
 * The renderer may keep a widget buffer and cursor, but it must send accepted text here and read
 * [state] directly. [update] advances revision only for admitted text/cursor edits;
 * [submit] captures the owner and revision, trims only the command payload, chooses submit versus
 * steer from the fixed runtime's current value (not a delayed renderer projection), and clears
 * only after the matching port returns.
 */
public interface ComposerViewModel : AutoCloseable {
    /** Atomic component state; no Mosaic or terminal type is exposed. */
    public val state: StateFlow<ComposerState>

    /**
     * Replaces text and cursor and returns its exact resulting revision.
     *
     * An unchanged text/cursor pair retains its revision unless clearing a failed submission.
     * Edits are ignored while an admitted command is submitting. The default cursor is the end
     * of the supplied text; widget edits pass their actual cursor explicitly.
     *
     * @throws IllegalArgumentException if [cursorOffset] is outside [text].
     * @throws IllegalStateException if the child is closed or revisions are exhausted.
     */
    public fun update(text: String, cursorOffset: Int = text.length): Long

    /**
     * Clears exactly [expectedRevision], normally after an owner adapter has consumed it.
     * Returns false for stale, submitting or closed state; an already-empty exact
     * draft is a successful no-op and does not advance revision.
     *
     * @throws IllegalStateException if an admitted clear would exhaust revisions.
     */
    public fun clear(expectedRevision: Long): Boolean

    /**
     * Admits one exact draft and preserves existing behavior:
     * running owners call [ComposerSteerPort] and return [ComposerSubmissionResult.QueuedAsSteer];
     * idle owners call [ComposerSubmitPort], clear after persistence, then call
     * [ComposerResumePort] and return [ComposerSubmissionResult.Submitted].
     *
     * Accepted/persisted work is never retried, compensated or rolled back. Caller cancellation
     * propagates as [CancellationException]; it does not invoke [ComposerCancellationPort].
     * Dependency cancellation also propagates. Non-cancellation failures retain the draft as
     * [ComposerSubmissionState.Failed] when the captured owner/revision is still current.
     * Late success/failure after close cannot publish local state; successful work still uses
     * its captured exact owner port. Replacing an Agent creates a different Composer with its
     * own dependencies rather than changing this child's identity and reusing old-owner ports.
     *
     * @throws CancellationException for caller, owner or dependency cancellation.
     * @throws IllegalStateException if an admitted transition exhausts revisions.
     * @throws IllegalArgumentException if a dependency failure cannot be represented with a
     * nonblank message.
     * @throws Exception if the dependency's failure reporter itself fails.
     */
    public suspend fun submit(expectedRevision: Long): ComposerSubmissionResult

    /**
     * Requests cancellation for the exact owner without changing the draft or inventing a queue.
     *
     * @throws CancellationException if the exact cancellation is cancelled.
     * @throws Exception for an exact-owner cancellation failure; the failure is also reported.
     */
    public fun cancel(): Unit

    /**
     * Closes observations and rejects future local commands. It does not cancel already accepted
     * submit/steer work, the borrowed owner scope, the Agent, or sibling request-user-input child.
     * Late callbacks are stale and cannot publish into this closed state.
     */
    override fun close(): Unit
}

/**
 * Creates a child with explicit owner identity and narrow ports.
 *
 * The factory borrows [ownerScope] and never closes it. Implementations observe runtime and
 * request-input projections there; host adapters own the scope and close the child at the exact
 * owner lifecycle boundary.
 */
public fun interface ComposerViewModelFactory {
    /**
     * @throws IllegalArgumentException if [ownerId] is invalid.
     * @throws IllegalStateException if dependency observation cannot be installed.
     */
    public fun create(
        ownerId: ComposerOwnerId,
        dependencies: ComposerDependencies,
        ownerScope: CoroutineScope,
    ): ComposerViewModel
}
