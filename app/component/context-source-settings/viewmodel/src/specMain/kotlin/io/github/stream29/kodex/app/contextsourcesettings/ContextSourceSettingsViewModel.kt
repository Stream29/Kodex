package io.github.stream29.kodex.app.contextsourcesettings

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Synchronous application-queue admission, never a persistence receipt. Accepted freezes all
 * arguments before returning; queued writes retain them after hide/close/owner cancellation.
 * Rejected admits nothing and carries only safe local text. Eventual conflicts/failures belong
 * to the application's single failure source, not a component-owned failure authority.
 */
public sealed interface ContextSourceWriteAdmission {
    public data object Accepted : ContextSourceWriteAdmission
    public data class Rejected(public val message: String) : ContextSourceWriteAdmission
}

/**
 * Suspending batch environment boundary, not filesystem validation or a duplicate-merge service.
 * Uses the actual backend Context environment and captured launch base; never the browser OS.
 * Accept absolute paths, ~ and ~/path; invalid syntax (blank, '$', other relative paths) has a null
 * lexical key. No user file is opened/scanned. Static built-in matches use the actual backend roots.
 * Saving retains trimmed user spelling, never the comparison key. The shared VM, not this port,
 * chooses whether to append or enable the first equivalent original entry.
 */
public fun interface ContextSourcePathPolicy {
    /**
     * Evaluate one candidate and all original paths in the same captured environment snapshot.
     * Evaluated.originalPaths must retain input order/cardinality, including invalid old entries.
     * Returned keys do not impose case/symlink identity or require existing/readable directories.
     * @throws kotlinx.coroutines.CancellationException when evaluation or its owner is cancelled.
     * @throws Exception Unexpected environment/transport failure, not ordinary invalid input.
     */
    public suspend fun evaluate(candidate: String, originalPaths: List<String>): ContextPathEvaluation
}

/**
 * Typed field ports, invoked synchronously on the owner/UI dispatcher. sources is ordered and
 * read-only. The adapter admits into the SAME application queue as other global edits; it captures
 * arguments immediately, never re-reads child state in a queued closure. Field baselines are checked
 * by existing queue/store rules and unrelated backend fields are merged, never replaced wholesale.
 *
 * operationFailure/reportFailure/dismissFailure bind the existing shared application reporting port
 * and source. No new error flag/store is created. Report/ack are nonthrowing, sanitized, local, and
 * exclude cancellation; eventual successful writes may clear the flag according to host policy.
 */
public interface ContextSourceSettingsDependencies {
    public val sources: StateFlow<AgentContextSourceSettings>
    public val pathPolicy: ContextSourcePathPolicy
    public val operationFailure: StateFlow<Boolean>
    /** @throws Exception Unexpected queue admission failure. */
    public fun setBuiltInEnabled(
        source: BuiltInContextSource, expected: Boolean, enabled: Boolean,
    ): ContextSourceWriteAdmission
    /** Add appends or enables the first normalized duplicate in place, using this frozen list.
     * @throws Exception Unexpected queue admission failure.
     */
    public fun replaceCustomSources(
        expected: List<AgentContextCustomSource>, updated: List<AgentContextCustomSource>,
    ): ContextSourceWriteAdmission
    /** Match the exact stored path and original value, preserving order and unrelated entries.
     * @throws Exception Unexpected queue admission failure.
     */
    public fun setCustomEnabled(
        original: AgentContextCustomSource, enabled: Boolean,
    ): ContextSourceWriteAdmission
    /** Match the exact stored path/original; no normalized retargeting.
     * @throws Exception Unexpected queue admission failure.
     */
    public fun removeCustom(original: AgentContextCustomSource): ContextSourceWriteAdmission
    public fun reportFailure(failure: Throwable): Unit
    public fun dismissFailure(): Unit
}

/** Reference identity of one dialog; forged, replaced, hidden and consumed tokens are ignored. */
public class ContextSourceDialogToken

/**
 * Hidden draws no overlay. Adding renders its VM-owned draft, safe validation/error, autofocus
 * path input and Cancel/Add actions. All callbacks use token identity, never "the latest dialog".
 */
public sealed interface ContextSourceSettingsDialog {
    public data object Hidden : ContextSourceSettingsDialog
    public data class Adding(
        public val token: ContextSourceDialogToken,
        public val draft: String = "",
        public val error: String? = null,
    ) : ContextSourceSettingsDialog
}

/**
 * Render five built-in toggles in enum order with fixed path explanations, then Add and custom
 * rows in persisted order (Enabled/Remove). Empty custom list displays None configured. Failure
 * is a projection of the shared source: generic acknowledgement, rendered here OR at the host.
 * Closed freezes the last sources, hides the dialog and renders nothing; never implies save success.
 */
public data class ContextSourceSettingsState(
    public val sources: AgentContextSourceSettings,
    public val dialog: ContextSourceSettingsDialog = ContextSourceSettingsDialog.Hidden,
    public val operationFailure: Boolean = false,
    public val closed: Boolean = false,
)

/**
 * Complete source list/add owner. Commands are dispatcher-confined; save starts owner-scoped
 * suspending evaluation and returns without waiting for its result. Unexpected
 * dependency errors are reported once without exposing exception text; rejection retains Add's
 * draft/error and list-command rejection reports the shared generic failure. Cancellation propagates
 * unchanged, never reports failure or automatically retries. No optimistic sources publication.
 * Close is idempotent/nonthrowing: stop child observations, discard draft, reject future commands;
 * owner completion has identical cleanup. Neither cancels/owns admitted application writes.
 * Creation starts only child observation jobs under the supplied owner scope; it creates no
 * stores or write queue and does not look up environment values.
 */
public interface ContextSourceSettingsViewModel : AutoCloseable {
    public val state: StateFlow<ContextSourceSettingsState>
    /** Replace any unaccepted dialog with a fresh identity/empty draft. */
    public fun add(): Unit
    /** Update matching dialog only; clears validation. */
    public fun updateDraft(token: ContextSourceDialogToken, path: String): Unit
    /**
     * Freeze trimmed input, exact token and original custom list before batch evaluation.
     * Exclude invalid input/static built-ins, append trimmed text or enable the first equivalent
     * entry without rewriting text/order. After await recheck exact token, draft, owner and baseline;
     * a changed baseline retains the draft for explicit retry, never silently rebases a stale result.
     * Accepted consumes token exactly once; double/reentrant/stale callbacks cannot re-admit it.
     * Invalid/rejected admission keeps the matching draft; never closes a replacement dialog.
     * Hide/dismiss/close cancel only unaccepted evaluation; admitted application writes survive.
     * Evaluation cancellation is not a reported failure and never retries automatically.
     * @throws kotlinx.coroutines.CancellationException Synchronous queue admission was cancelled.
     */
    public fun save(token: ContextSourceDialogToken): Unit
    /** @throws kotlinx.coroutines.CancellationException Queue admission was cancelled. */
    public fun setBuiltInEnabled(source: BuiltInContextSource, enabled: Boolean): Unit
    /** Missing exact stored path is a no-op.
     * @throws kotlinx.coroutines.CancellationException Queue admission was cancelled.
     */
    public fun setCustomEnabled(path: String, enabled: Boolean): Unit
    /** Missing exact stored path is a no-op.
     * @throws kotlinx.coroutines.CancellationException Queue admission was cancelled.
     */
    public fun removeCustom(path: String): Unit
    /** Cancel/Escape only the exact dialog; discard its unaccepted draft. */
    public fun dismiss(token: ContextSourceDialogToken): Unit
    /** Page navigation discards the dialog but keeps observations and shared failure. */
    public fun hidePage(): Unit
    /** Acknowledge the shared failure source, no-op after close. */
    public fun dismissFailure(): Unit
    public override fun close(): Unit
}
