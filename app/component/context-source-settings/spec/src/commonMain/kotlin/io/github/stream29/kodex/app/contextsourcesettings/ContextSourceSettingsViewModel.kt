package io.github.stream29.kodex.app.contextsourcesettings

import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import kotlinx.coroutines.CoroutineScope
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
 * Environment boundary, not filesystem validation. normalize accepts absolute paths, ~ and ~/path,
 * rejects blank, '$' and other relative paths with null, and returns a stable comparison string.
 * No user file is opened/scanned. Both methods use the same captured frontend environment; saving
 * retains the user's trimmed spelling, not this normalized value. Backend interpretation is separate.
 */
public interface ContextSourcePathPolicy {
    /** Normalized Agents/Kodex/Codex home roots; Git root and cwd are not static exclusions.
     * @throws Exception Unexpected environment lookup failure; reported by save, not invalid input.
     */
    public val builtInNormalizedPaths: Set<String>
    /** @throws Exception Unexpected environment/policy failure, not ordinary invalid input. */
    public fun normalize(path: String): String?
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
 * Complete source list/add owner. Commands are dispatcher-confined and synchronous. Unexpected
 * dependency errors are reported once without exposing exception text; rejection retains Add's
 * draft/error and list-command rejection reports the shared generic failure. Cancellation propagates
 * unchanged, never reports failure or automatically retries. No optimistic sources publication.
 * Close is idempotent/nonthrowing: stop child observations, discard draft, reject future commands;
 * owner completion has identical cleanup. Neither cancels/owns admitted application writes.
 */
public interface ContextSourceSettingsViewModel : AutoCloseable {
    public val state: StateFlow<ContextSourceSettingsState>
    /** Replace any unaccepted dialog with a fresh identity/empty draft. */
    public fun add(): Unit
    /** Update matching dialog only; clears validation. */
    public fun updateDraft(token: ContextSourceDialogToken, path: String): Unit
    /**
     * Trim input, normalize/validate, exclude static built-ins, then freeze current custom list and
     * append trimmed text or enable its first equivalent entry without rewriting its text/order.
     * Accepted consumes token exactly once; double/reentrant/stale callbacks cannot re-admit it.
     * Invalid/rejected admission keeps the matching draft; never closes a replacement dialog.
     * @throws kotlinx.coroutines.CancellationException Policy or queue admission was cancelled.
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

/** Creates only child observation jobs in ownerScope; no stores, environment lookup or write queue. */
public fun interface ContextSourceSettingsViewModelFactory {
    public fun create(
        dependencies: ContextSourceSettingsDependencies, ownerScope: CoroutineScope,
    ): ContextSourceSettingsViewModel
}
