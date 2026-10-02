package io.github.stream29.kodex.app.usagereset.contract

import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Dependency-only frontend ports. No Global Settings/RPC contract, credentials,
 * account/attempt identity, prepare operation or result replay is exposed.
 * Dependencies remain owned by the host and are never closed by the component.
 */
public interface UsageResetDependencies {
    /**
     * Shared account-isolated projection. previous is only same-account fallback;
     * account changes must clear it in the host/backend. Authentication and usage
     * streams are not an atomic account lock. Construction does not refresh or
     * subscribe with a second mutable mirror; commands read the current value.
     */
    public val usage: StateFlow<SettingsAccountUsageState>

    /**
     * Consume ONLY this non-blank credit ID, once per admitted confirmation.
     * The adapter must retain backend command-start current-account admission and
     * supplier validation, not silently substitute a credit or bind an account at
     * confirmation time. The four outcomes are definitive; an ordinary exception
     * leaves the remote outcome unknown. Waiting cancellation cannot promise
     * rollback of a backend-accepted request. No automatic retry is permitted.
     *
     * @throws CancellationException if waiting/the dependency is cancelled.
     * @throws Exception if dispatch or receipt fails, even after remote acceptance.
     */
    public suspend fun consume(creditId: String): CodexRateLimitResetOutcome

    /**
     * Request a supplier usage refresh, not a cached read or credit consumption.
     * Unit does not guarantee Available or synchronous flow convergence. Called
     * once after an ordinary consume failure, and separately on explicit
     * refreshChoices. Normal outcomes do NOT trigger an extra refresh. Failure
     * preserves the unknown failure; it cannot erase a known consume result.
     *
     * @throws CancellationException if waiting/the dependency is cancelled.
     * @throws Exception if refresh fails.
     */
    public suspend fun refreshUsage(): Unit
}

/**
 * Typed construction boundary; creates an initially Hidden child linked to
 * create's ownerScope, with no refresh/consume side effects. The caller owns close.
 */
public fun interface UsageResetViewModelFactory {
    /**
     * Retain [dependencies] without owning their lifetime. Link a private child
     * Job to [ownerScope]'s Job and use its interaction dispatcher. A cancelled
     * owner creates a permanently inactive, Hidden child. Close never cancels
     * the owner's Job. No suspend operation or backend command runs in create.
     */
    public fun create(
        dependencies: UsageResetDependencies,
        ownerScope: CoroutineScope,
    ): UsageResetViewModel
}

/**
 * Reusable dialog state owner. Commands and dependency continuations are confined
 * to the explicit owner's interaction dispatcher; no cross-thread command safety
 * is promised. All commands are non-suspending and no-op after owner cancellation
 * or close. Normal dependency failures become states; cancellation is rethrown
 * inside the owned job, never interpreted as a normal result or retried.
 *
 * Consume admission sets Consuming synchronously, suppressing double calls even
 * before the coroutine starts. A normal result is published unchanged without
 * an extra refresh. Ordinary failure is published before one refresh; commands
 * stay blocked until that refresh finishes. Cancellation of an active consume
 * wait performs no refresh and leaves the last published state; after the job
 * unwinds, show/dismiss can recover the local dialog without replaying consumption.
 *
 * close cancels only this child's jobs and hides immediately. Each continuation
 * checks the owner and its own job before publishing or beginning a follow-up.
 * Even noncooperative late dependencies cannot restore a closed frontend. This
 * is local lifecycle protection, NOT remote rollback or exactly-once delivery.
 */
public interface UsageResetViewModel : AutoCloseable {
    /** Stable readonly flow; initially Hidden. Sole authority for all dialog branches. */
    public val state: StateFlow<UsageResetState>

    /**
     * Rebuild fresh choices from actual current snapshot credit details. Null
     * details, known empty details, unavailable usage and count-only usage yield
     * PreparationFailed. Blank IDs are excluded; duplicate IDs cannot be selected.
     * No consume/refresh side effects; no-op while consumption/its refresh is active.
     */
    public fun show(): Unit

    /**
     * Only Choosing accepts exactly one matching non-blank credit ID and publishes
     * Confirming. Unknown/blank/ambiguous IDs and other states are ignored. Never
     * autochooses, prepares or consumes.
     */
    public fun select(creditId: String): Unit

    /**
     * Accept ONLY the currently published object by reference identity and a
     * non-blank specific creditId. Equal copies, stale callbacks and duplicates
     * do nothing. Consume the captured ID once, then publish Completed without
     * an extra refresh, or ConsumeFailed and refresh once. No account binding or
     * replay extension.
     */
    public fun confirm(expected: UsageResetState.Confirming): Unit

    /** Only Confirming: rebuild from fresh usage, invalidating the previous handle. */
    public fun back(): Unit

    /** Only ConsumeFailed: fresh selection, not consume or refresh; user confirms anew. */
    public fun retry(): Unit

    /**
     * Only PreparationFailed: refresh once (duplicate refresh clicks suppressed),
     * then rebuild from the current projection. Ordinary failure keeps the
     * PreparationFailed state. Dismiss/reopen invalidates that refresh's callback;
     * no late callback may reopen a dismissed dialog.
     */
    public fun refreshChoices(): Unit

    /**
     * Hide without closing the reusable child; ignored throughout live consumption
     * and its follow-up refresh. Invalidates pending choice-refresh rendering.
     * Page/source-switch adapters use this rather than promise remote cancellation.
     */
    public fun dismiss(): Unit

    /**
     * Idempotently hide, cancel local jobs and reject later commands. Never close
     * the shared usage store or dependencies; never promise backend rollback.
     */
    override fun close(): Unit
}
