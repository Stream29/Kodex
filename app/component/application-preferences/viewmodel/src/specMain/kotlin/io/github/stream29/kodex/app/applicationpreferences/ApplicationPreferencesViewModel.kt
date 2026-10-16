package io.github.stream29.kodex.app.applicationpreferences

import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SubmitKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Accepted freezes the canonical key in the application queue, not a persistence receipt. */
public enum class PreferencesWriteAdmission { Accepted, Rejected }

/**
 * widths is the application-owned transient Pair(left,right), initially applicationWidth/4;
 * closing/reopening a child never resets/persists it. Width sources may contain 0..3: projection
 * clamps display only, NEVER corrects/re-writes the source during refresh.
 * setLeftWidth/setRightWidth must resize synchronously with the CURRENT other width from widths,
 * not a child/renderer snapshot. Resizing is not queued or persisted.
 *
 * newLineKey is the sole persisted input setting; setNewLineKey admits a frozen enum value to the
 * existing frontend application queue, preserving other frontend fields and accepted writes after
 * close/owner cancellation. Rejection is not persistence failure/success. Independent key/width
 * observations are not promised atomic. operationFailure/reportFailure/dismissFailure bind the
 * existing shared reporting source/port, nonthrowing, sanitized and excluding cancellation.
 */
public interface ApplicationPreferencesDependencies {
    public val widths: StateFlow<Pair<Int, Int>>
    public val newLineKey: StateFlow<NewLineKey>
    public val operationFailure: StateFlow<Boolean>
    /** @throws IllegalArgumentException columns is negative.
     * @throws Exception Unexpected resize failure.
     */
    public fun setLeftWidth(columns: Int): Unit
    /** @throws IllegalArgumentException columns is negative.
     * @throws Exception Unexpected resize failure.
     */
    public fun setRightWidth(columns: Int): Unit
    /** @throws Exception Unexpected queue admission failure. */
    public fun setNewLineKey(newLineKey: NewLineKey): PreferencesWriteAdmission
    public fun reportFailure(failure: Throwable): Unit
    public fun dismissFailure(): Unit
}

/**
 * Render Sidebars (left then right width, columns explanation, '-' then '+'), followed by Input
 * (New line key then Submit key). Widths are source values clamped to MinimumSidebarWidthColumns
 * for display, not saved or automatically applied. Minus disables at minimum, plus at Int.MAX_VALUE;
 * +/- sends the displayed snapshot's columns +/-1 through explicit set-width commands.
 * submitKey derives solely from the canonical newLineKey pairing, never a second mutable setting.
 * Failure projects the shared generic acknowledgement, rendered here OR at host; closed draws nothing.
 */
public data class ApplicationPreferencesState(
    public val leftWidth: Int,
    public val rightWidth: Int,
    public val newLineKey: NewLineKey,
    public val operationFailure: Boolean = false,
    public val closed: Boolean = false,
) {
    public val submitKey: SubmitKey get() = newLineKey.submitKey
}

/**
 * Dispatcher-confined synchronous commands. Explicit widths preserve renderer snapshot +/- semantics,
 * forward requested nonnegative columns unchanged, and use the adapter's current other width.
 * No silent correction on source updates, optimistic state or width persistence. Unknown port errors
 * and rejected key admission report the shared failure once; cancellation propagates without reporting
 * or retry. close is idempotent, freezes state, stops child observation and rejects future commands,
 * but never resets application widths or cancels queued keys; owner completion does the same.
 */
public interface ApplicationPreferencesViewModel : AutoCloseable {
    public val state: StateFlow<ApplicationPreferencesState>
    /** After close this is a no-op, including invalid input.
     * @throws IllegalArgumentException columns is negative on an active child.
     * @throws kotlinx.coroutines.CancellationException Resize was cancelled.
     */
    public fun setLeftWidth(columns: Int): Unit
    /** @throws IllegalArgumentException columns is negative on an active child.
     * @throws kotlinx.coroutines.CancellationException Resize was cancelled.
     */
    public fun setRightWidth(columns: Int): Unit
    /** @throws kotlinx.coroutines.CancellationException Admission was cancelled. */
    public fun setNewLineKey(newLineKey: NewLineKey): Unit
    /** Map through SubmitKey.newLineKey, admitting exactly one canonical-key write.
     * @throws kotlinx.coroutines.CancellationException Admission was cancelled.
     */
    public fun setSubmitKey(submitKey: SubmitKey): Unit
    /** No business draft; menu/focus belongs to renderer. */
    public fun hidePage(): Unit
    public fun dismissFailure(): Unit
    public override fun close(): Unit
}

/** Creates observation jobs only; no store, queue, width initialization or resize at construction. */
public fun interface ApplicationPreferencesViewModelFactory {
    public fun create(
        dependencies: ApplicationPreferencesDependencies, ownerScope: CoroutineScope,
    ): ApplicationPreferencesViewModel
}
