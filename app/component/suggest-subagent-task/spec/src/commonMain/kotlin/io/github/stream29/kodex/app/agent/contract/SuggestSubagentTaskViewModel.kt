package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Exact batch draft; cwd configures new Sessions, never the source Agent. */
public data class SuggestedSessionConfiguration(
    public val model: OpenAiModelId,
    public val reasoningEffort: ReasoningEffort,
    public val serviceTier: ServiceTier,
    public val cwd: Path,
    public val requestUserInputMode: RequestUserInputMode,
)

/** Atomic confirmation, configuration and feedback state for one projected call. */
public sealed interface SuggestSubagentTaskState {
    /** Render no suggestion panel. */
    public data object Idle : SuggestSubagentTaskState

    /**
     * Render tasks in order and all configuration controls, followed by Accept/Reject.
     * Accept is the explicit creation confirmation; no second confirmation state exists.
     * [rejecting] reveals optional feedback and Submit rejection; choosing Reject alone
     * never completes the call. Disable all edits and decisions while [submitting].
     * Failed submissions restore editing with the same draft, without a new error banner.
     * [revision] preserves the existing unchecked Long increment behavior.
     */
    public data class Pending(
        public val callId: String,
        public val arguments: SuggestSubagentTaskArgs,
        public val configuration: SuggestedSessionConfiguration,
        public val feedback: String = "",
        public val revision: Long = 0,
        public val submitting: Boolean = false,
        public val rejecting: Boolean = false,
    ) : SuggestSubagentTaskState
}

/** Submission admission/outcome; failure cannot establish whether side effects happened. */
public sealed interface SuggestSubagentTaskSubmissionResult {
    /** Completion returned and the host continuation callback returned. */
    public data object Submitted : SuggestSubagentTaskSubmissionResult
    /** Closed/idle, mismatched call/revision, or lost submission CAS. */
    public data object Stale : SuggestSubagentTaskSubmissionResult
    /** Exact current revision is already submitting. */
    public data object Busy : SuggestSubagentTaskSubmissionResult
    /** Ordinary creation/completion/resume failure; no automatic retry or compensation. */
    public data class Failed(public val message: String) : SuggestSubagentTaskSubmissionResult
}

/**
 * Agent-owned suggestion interaction. Commands/observation run on the owner's interaction
 * dispatcher. New call ids sample defaults once and reset drafts; repeated same-call
 * projections are ignored, even when arguments change. Hiding/tab switching does not close.
 * The renderer owns only menus, anchors, focus and scrolling, not rejecting/configuration.
 */
public interface SuggestSubagentTaskViewModel : AutoCloseable {
    /** Full business interaction snapshot, not a writable backend pending state. */
    public val state: StateFlow<SuggestSubagentTaskState>

    /**
     * Read-only live catalog. Missing configured model/effort/tier remains in the draft and
     * visible; catalog changes must not silently rewrite it. Menus include the current model,
     * use advertised efforts (or current effort) and advertised tiers (or Default).
     */
    public val models: StateFlow<List<ModelInfo>>

    /**
     * Raw feedback; admitted edits increment revision even if unchanged.
     * False for closed/idle/stale-call/submitting state; no admission exception.
     */
    public fun updateFeedback(callId: String, text: String): Boolean

    /**
     * Enters/leaves rejection editing without submitting or clearing feedback.
     * An unchanged mode is a successful no-op; a changed mode increments revision.
     * False for closed/idle/stale-call/submitting state; no admission exception.
     */
    public fun setRejecting(callId: String, rejecting: Boolean): Boolean

    /**
     * Replaces the entire batch draft. Use field commands for delayed selections so unrelated
     * newer fields survive. Admitted replacements increment revision even if unchanged.
     * False for closed/idle/stale-call/submitting state; no catalog validation or exception.
     */
    public fun updateConfiguration(
        callId: String,
        configuration: SuggestedSessionConfiguration,
    ): Boolean

    /**
     * Atomically edits the menu's model/effort/tier triple on the latest configuration.
     * Increments revision; false for closed/idle/stale-call/submitting state.
     * Missing catalog values are valid, not exceptions.
     */
    public fun updateModelConfiguration(
        callId: String,
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ): Boolean

    /**
     * Edits only question mode on the latest configuration; increments revision.
     * False for closed/idle/stale-call/submitting state; no admission exception.
     */
    public fun updateRequestUserInputMode(callId: String, mode: RequestUserInputMode): Boolean

    /**
     * Edits only cwd on the latest configuration; increments revision. The host opens a
     * Working Directory child bound to this exact call, then its selection dependency invokes
     * this command. No filesystem check, popup routing or source-Agent cwd write occurs here.
     * False for closed/idle/stale-call/submitting state; no admission exception.
     */
    public fun updateWorkingDirectory(callId: String, directory: Path): Boolean

    /**
     * All edit commands return false for closed/idle/stale-call/submitting state and do not
     * throw for invalid admission. Configuration values are not restricted to the live catalog.
     *
     * Submission checks call and revision before Busy, then freezes the complete draft.
     * Accept creates Sessions, completes the original Accepted event, then requests resume.
     * Reject completes Rejected then resumes, with no creation. Feedback is included only
     * on rejection when nonblank, and is passed untrimmed. These are separate side effects,
     * not a transaction: no exactly-once guarantee, retry, deletion or compensating creation.
     *
     * Accepted work belongs to ownerScope; cancelling the caller stops only its wait.
     * Owner/dependency cancellation retains Submitting until replacement or close. Ordinary
     * failure restores editing only if the frozen state is still current. Late results do not
     * replace newer snapshots, but success still clears the internal pending event and resumes
     * after replacement/close, matching existing behavior. Failed completion after creation
     * leaves those Sessions created; an explicit retry can create another batch.
     *
     * @throws CancellationException if the caller, owner or dependency is cancelled.
     */
    public suspend fun submit(
        callId: String,
        expectedRevision: Long,
        accepted: Boolean,
    ): SuggestSubagentTaskSubmissionResult

    /**
     * Idempotently publishes Idle, rejects edits and releases the owned pending observer.
     * Does not cancel accepted submissions, owner scope, source Agent or created Sessions.
     */
    override fun close(): Unit
}

/** Capabilities bound to the source Agent, not the currently selected Application tab. */
public interface SuggestSubagentTaskDependencies {
    /** Existing first-pending projection; observation never authorizes creation. */
    public val pending: Flow<PendingSuggestSubagentTaskToolEvent?>
    /** Live read-only catalog; component does not own or close this source. */
    public val models: StateFlow<List<ModelInfo>>

    /**
     * Samples defaults on a new call only. No later default/catalog change overwrites edits.
     * @throws Exception if defaults cannot be read; follows observation scope failure policy.
     */
    public fun defaultConfiguration(): SuggestedSessionConfiguration

    /**
     * Uses the existing batch entrypoint. Registration/onCreated/navigation belong to the
     * adapter. A failed/lost return does not prove no Sessions were created.
     * @throws CancellationException if the bound wait/operation is cancelled.
     * @throws Exception if batch creation fails.
     */
    public suspend fun createSessions(
        arguments: SuggestSubagentTaskArgs,
        configuration: SuggestedSessionConfiguration,
    ): List<SuggestedSessionMeta>

    /**
     * Completes the original tool event, returning its committed index (not used by the child).
     * Never implicitly resumes or compensates creation.
     * @throws CancellationException if the bound wait/operation is cancelled.
     * @throws Exception if completion fails.
     */
    public suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int

    /**
     * Requests the existing continuation of the same source Agent; return need not await it.
     * @throws Exception if continuation cannot be requested.
     */
    public fun resumeRuntime(): Unit
}

/** Creates an Agent-owned child; borrows but never cancels the supplied scope. */
public fun interface SuggestSubagentTaskViewModelFactory {
    /**
     * Observes pending until close, owner termination or upstream completion (which closes
     * the child). Accepted submissions run directly in [ownerScope]; upstream failures use
     * that scope's existing coroutine failure policy.
     */
    public fun create(
        dependencies: SuggestSubagentTaskDependencies,
        ownerScope: CoroutineScope,
    ): SuggestSubagentTaskViewModel
}
