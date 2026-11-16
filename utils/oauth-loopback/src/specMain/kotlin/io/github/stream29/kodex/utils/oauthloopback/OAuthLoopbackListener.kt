package io.github.stream29.kodex.utils.oauthloopback

import kotlinx.coroutines.CoroutineScope

/**
 * A local HTTP callback resource, not an OAuth attempt or a provider client.
 * The borrowed scope owns a separately cancellable child; closing never cancels that scope.
 */
public interface OAuthLoopbackListener {
    /** Selected candidate with the resolved port. No authorization query is included. */
    public val redirectUri: String

    /**
     * Installs state with atomic admission. Non-blank `code` or `error` is always required.
     * For a structurally valid callback [admit] runs once before it is claimed; false rejects it
     * without consumption.
     */
    public suspend fun armStateWithAdmission(
        state: String,
        admit: suspend () -> Boolean = { true },
    ): Unit = error("Atomic callback admission is unavailable.")

    /**
     * Claims the sole wait and returns the redirect plus the accepted raw query.
     * Requests before arming, duplicate state/code/error fields, blank values, and requests
     * without exactly one of code/error are rejected.
     * The first valid callback wins atomically; subsequent valid callbacks are rejected too.
     * Cancellation of this wait does not close the listener; callers must close in finally.
     *
     * @throws IllegalStateException for a second wait or an engine failure.
     * @throws kotlinx.coroutines.CancellationException when closed, owner-cancelled or wait-cancelled.
     */
    public suspend fun awaitCallback(): String

    /**
     * Idempotently cancels the pending callback and joins this resource's engine and child jobs.
     * Cleanup runs even when the caller is cancelled. Concurrent closes await the same cleanup.
     *
     * @throws IllegalStateException if engine cleanup fails, without exposing callback parameters.
     */
    public suspend fun closeAndJoin()
}

/**
 * Binds an HTTP listener to 127.0.0.1, trying the supplied candidates in order on bind failure.
 * Candidates must use host localhost or 127.0.0.1, without user info, query or fragment.
 * The candidate's encoded path selects the GET route; its host spelling is retained in the result.
 * Candidate ports are preserved unless zero requests an ephemeral port. No ports are invented.
 * URL validation occurs as each candidate is reached; an invalid candidate stops fallback.
 * Callback GET requests require the reserved Host spelling, actual port and exact encoded path.
 *
 * The scope must contain a Job. Its completion waits for resource cleanup. Failure/cancellation
 * while opening releases any acquired engine before returning; failures never cancel the scope.
 * No App/RPC, provider selection, PKCE, credentials or business attempt state is managed here.
 */
public expect suspend fun openOAuthLoopbackListener(
    scope: CoroutineScope,
    redirectCandidates: List<String>,
): OAuthLoopbackListener
