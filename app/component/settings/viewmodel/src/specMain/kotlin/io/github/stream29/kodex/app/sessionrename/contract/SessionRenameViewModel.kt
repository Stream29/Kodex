package io.github.stream29.kodex.app.sessionrename.contract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Operation bound to the exact session and, where applicable, revision captured
 * when the editor opened. Implementations must not resolve the active tab anew.
 *
 * Application adapters await their session operation. Settings adapters hand
 * off to their revision-checked command: a normal return can mean a queued write
 * or a stale/closed no-op, not persistence or successful revision matching.
 * This component does not own the source, perform storage writes itself, or
 * reinterpret stale-revision outcomes.
 */
public fun interface SessionRenameDependencies {
    /**
     * Dispatches/applies an already trimmed [name] to the captured target.
     *
     * @throws CancellationException if the caller or underlying operation is cancelled.
     * @throws Exception if the bound operation fails synchronously or while awaited.
     */
    public suspend fun rename(name: String): Unit
}

/**
 * One short-lived session-name editor. The creation seam binds the initial name
 * and [SessionRenameDependencies]; renderer and component never access a parent
 * Application, Settings, Session or RPC implementation.
 *
 * Render [draftName] as the editable text, not as the current persisted title.
 * Maintain cursor/focus locally. Plain Enter submits only a nonblank draft;
 * modified Enter does not submit. Do not render Rename/Cancel action buttons.
 * Dismissal closes only this child and not its dependency/target.
 *
 * Calls are confined to the owner's interaction dispatcher. There is no queue
 * or duplicate-submission suppression: a host must serialize submissions if
 * needed. Failures and cancellation propagate, leaving the draft intact.
 * The host dismisses its exact open handle after successful return, which for
 * a Settings adapter is not a persistence acknowledgment. Completion from an
 * already closed child must not dismiss a replacement popup.
 */
public interface SessionRenameViewModel : AutoCloseable {
    /** Initialized once from the captured title; later external renames do not overwrite it. */
    public val draftName: StateFlow<String>

    /** Whether new commands are accepted. Closed children must no longer be rendered interactively. */
    public val isActive: Boolean

    /** Publishes the exact untrimmed input; ignored after [close]. */
    public fun updateDraftName(name: String): Unit

    /**
     * Snapshots and trims the latest draft and invokes the bound operation once.
     * Does not close the editor or change the draft. The renderer prevents blank
     * submissions; programmatic callers are responsible for the same validation.
     *
     * @throws IllegalStateException if the child was already closed.
     * @throws CancellationException if the caller or bound operation is cancelled.
     * @throws Exception if the bound operation fails; no dismissal is implied.
     */
    public suspend fun rename(): Unit

    /**
     * Idempotently rejects future submissions and ignores future edits. Does not
     * cancel/roll back an operation already started or close the bound target.
     */
    override fun close(): Unit
}
