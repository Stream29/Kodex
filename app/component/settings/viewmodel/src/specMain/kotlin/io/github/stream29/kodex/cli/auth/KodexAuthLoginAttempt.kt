package io.github.stream29.kodex.cli.auth

import kotlinx.coroutines.CancellationException

/**
 * One frontend-local browser login attempt with a destination fixed at creation.
 *
 * The OpenAI Login child receives this capability from its declared dependency.
 * Listener and backend protocol details remain in the dependency implementation;
 * no credentials or backend-owned objects are exposed to the child.
 */
public interface KodexAuthLoginAttempt {
    /** One-time browser authorization URL; never persist it or include it in diagnostics. */
    public val authorizationUrl: String

    /**
     * Waits for authorization and credential commit, not merely browser launch.
     *
     * Completion does not select a different authentication source. The dependency
     * owns local listener cleanup and pending release on success, failure and cancellation.
     * Cancelling a local wait/releasing its view never cancels accepted backend exchange/commit.
     *
     * @throws CancellationException when the wait or this attempt is cancelled.
     * @throws Exception when authorization, callback delivery or credential commit fails.
     * Failure messages supplied to the child must not contain credentials or callback data.
     */
    public suspend fun awaitCompletion()

    /**
     * Releases local waiting/listener ownership and abandons only pending backend login state.
     * Safe after completion; accepted exchange/persistence remains backend-owned and is not revoked.
     * Non-suspending, idempotent request; cleanup is bounded/asynchronous, not a commit receipt.
     * View/renderer disposal and owner cancellation use release, never implicit explicit cancel.
     */
    public fun release()

    /**
     * Requests cancellation of this exact attempt and its local callback listener.
     *
     * Safe after completion; does not delete or roll back committed credentials.
     * This non-suspending request does not promise that asynchronous cleanup has
     * completed when it returns.
     */
    public fun cancel()
}
