package io.github.stream29.kodex.openai.accountusage

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/** Observable, non-persistent account usage for the currently authenticated Codex account. */
public interface CodexAccountUsageStore : AutoCloseable {
    /** Latest account-isolated usage state. */
    public val state: StateFlow<CodexAccountUsageState>

    /** Reloads all available usage sections for the current account. */
    public suspend fun refresh()

    /** Creates one account-bound, idempotent reset attempt. */
    public suspend fun createResetAttempt(creditId: String? = null): CodexRateLimitResetAttempt

    /**
     * Consumes [attempt] and refreshes usage after a definitive backend result.
     *
     * A transport failure leaves the attempt reusable with the same idempotency key.
     */
    public suspend fun consumeResetAttempt(
        attempt: CodexRateLimitResetAttempt,
    ): CodexRateLimitResetOutcome
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

    /** One confirmed reset attempt is being submitted. */
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

/** One atomically published account-usage aggregate. */
public data class CodexAccountUsageSnapshot(
    public val rateLimits: List<CodexAccountRateLimit>,
    public val resetCredits: CodexRateLimitResetCredits,
    public val tokenUsage: CodexAccountTokenUsage? = null,
    public val unavailableSections: Set<CodexAccountUsageSection> = emptySet(),
    public val fetchedAt: Instant,
)

/** Optional usage sections whose failure does not invalidate the rate-limit snapshot. */
public enum class CodexAccountUsageSection {
    ResetCreditDetails,
    TokenUsage,
}

/** Current status and windows for one backend-defined rate limit. */
public data class CodexAccountRateLimit(
    public val name: String,
    public val meteredFeature: String,
    public val allowed: Boolean,
    public val limitReached: Boolean,
    public val primaryWindow: CodexAccountRateLimitWindow? = null,
    public val secondaryWindow: CodexAccountRateLimitWindow? = null,
)

/** Usage and reset timing for one rate-limit window. */
public data class CodexAccountRateLimitWindow(
    public val usedPercent: Long,
    public val durationSeconds: Long,
    public val resetAfterSeconds: Long,
    public val resetsAt: Instant,
)

/** Available reset count and optional backend-provided detail rows. */
public data class CodexRateLimitResetCredits(
    public val availableCount: Long?,
    public val credits: List<CodexRateLimitResetCredit>? = null,
)

/** One currently available Codex rate-limit reset credit. */
public data class CodexRateLimitResetCredit(
    public val id: String,
    public val grantedAt: Instant?,
    public val expiresAt: Instant?,
    public val title: String? = null,
    public val description: String? = null,
)

/** Account-wide token activity and optional daily buckets. */
public data class CodexAccountTokenUsage(
    public val lifetimeTokens: Long? = null,
    public val peakDailyTokens: Long? = null,
    public val longestRunningTurnSeconds: Long? = null,
    public val currentStreakDays: Long? = null,
    public val longestStreakDays: Long? = null,
    public val dailyUsageBuckets: List<CodexAccountTokenUsageDailyBucket>? = null,
)

/** Tokens used in one backend-defined calendar-day bucket. */
public data class CodexAccountTokenUsageDailyBucket(
    public val startDate: String,
    public val tokens: Long,
)

/** Stable idempotency data for one logical reset attempt. */
public data class CodexRateLimitResetAttempt(
    public val idempotencyKey: String,
    public val creditId: String? = null,
)

/** Definitive business outcome from consuming a reset credit. */
public enum class CodexRateLimitResetOutcome {
    Reset,
    NothingToReset,
    NoCredit,
    AlreadyRedeemed,
}
