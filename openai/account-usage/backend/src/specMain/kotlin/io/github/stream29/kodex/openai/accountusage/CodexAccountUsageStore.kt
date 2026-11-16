package io.github.stream29.kodex.openai.accountusage

import kotlinx.coroutines.flow.StateFlow

/**
 * Observable, non-persistent usage for the currently authenticated Codex account.
 *
 * Published snapshots and reset attempts are account-bound: switching accounts
 * must not expose the previous account's data or permit its attempt to be used
 * under the new account. This store owns its observation and refresh work, not
 * the injected authentication store or OpenAI client.
 */
public interface CodexAccountUsageStore : AutoCloseable {
    /**
     * Latest account-isolated state. Loading and failure may retain a previous
     * snapshot only for the same account; an unavailable account has none.
     */
    public val state: StateFlow<CodexAccountUsageState>

    /**
     * Reloads usage for the current account. Without authentication, publishes
     * [CodexAccountUsageState.Unavailable]. While that account remains
     * current, publishes Loading and then Available or Failed; a
     * non-cancellation mandatory usage failure retains only a same-account
     * previous snapshot. A non-cancellation failure of an optional section
     * does not discard mandatory usage and is recorded in
     * [CodexAccountUsageSnapshot.unavailableSections].
     */
    public suspend fun refresh()

    /**
     * Creates a reset attempt for the current Available snapshot with a new
     * idempotency key. A blank [creditId] is treated as no selected credit.
     * Creating another attempt replaces the previous claimable attempt.
     *
     * @throws IllegalStateException if no account is authenticated or the
     * current account has no available usage snapshot.
     */
    public suspend fun createResetAttempt(creditId: String? = null): CodexRateLimitResetAttempt

    /**
     * Consumes [attempt] and refreshes usage after a definitive backend result.
     *
     * The attempt must belong to the current account. Submitting it publishes
     * Redeeming with the prior same-account snapshot.
     * A transport or provider failure before a definitive result leaves the
     * attempt reusable with the same idempotency key. A definitive result
     * consumes the attempt; if the follow-up usage refresh fails while that
     * account remains current, the business outcome remains definitive while
     * state reports a refresh failure.
     * An account switch discards old account state while still returning the
     * definitive outcome.
     *
     * @throws IllegalArgumentException if the attempt has a blank idempotency key.
     * @throws IllegalStateException if no account is authenticated, the attempt
     * does not belong to the current account, or no current usage snapshot exists.
     */
    public suspend fun consumeResetAttempt(
        attempt: CodexRateLimitResetAttempt,
    ): CodexRateLimitResetOutcome

    /** Stops owned observation/refresh work without closing injected dependencies. */
    override fun close()
}

/** Loading and operation state for the current Codex account usage snapshot. */
public sealed interface CodexAccountUsageState {
    /** Authentication cannot currently provide an account for usage requests. */
    public data object Unavailable : CodexAccountUsageState

    /** A snapshot is being loaded; [previous] remains account-safe fallback data when present. */
    public data class Loading(
        public val previous: CodexAccountUsageSnapshot? = null,
    ) : CodexAccountUsageState

    /** All mandatory usage data was loaded. */
    public data class Available(
        public val snapshot: CodexAccountUsageSnapshot,
    ) : CodexAccountUsageState

    /** The latest request failed; [previous] remains usable when it belongs to the same account. */
    public data class Failed(
        public val message: String,
        public val previous: CodexAccountUsageSnapshot? = null,
    ) : CodexAccountUsageState

    /** A claimed reset attempt is being submitted with a same-account snapshot. */
    public data class Redeeming(
        public val snapshot: CodexAccountUsageSnapshot,
        public val attempt: CodexRateLimitResetAttempt,
    ) : CodexAccountUsageState
}

/** Returns the same-account snapshot retained by a usage state, when one exists. */
public fun CodexAccountUsageState.snapshotOrNull(): CodexAccountUsageSnapshot? =
    when (this) {
        is CodexAccountUsageState.Available -> snapshot
        is CodexAccountUsageState.Failed -> previous
        is CodexAccountUsageState.Loading -> previous
        is CodexAccountUsageState.Redeeming -> snapshot
        is CodexAccountUsageState.Unavailable -> null
    }

/**
 * Stable idempotency data for one logical reset attempt.
 *
 * Reuse the same [idempotencyKey] after a non-definitive request failure;
 * creating a different attempt does not retry the same logical reset.
 * [creditId] is `null` when no particular credit was selected.
 *
 * @property idempotencyKey Key identifying the logical reset across retries.
 * @property creditId Optional backend identifier of the selected reset credit.
 */
public data class CodexRateLimitResetAttempt(
    public val idempotencyKey: String,
    public val creditId: String? = null,
)
