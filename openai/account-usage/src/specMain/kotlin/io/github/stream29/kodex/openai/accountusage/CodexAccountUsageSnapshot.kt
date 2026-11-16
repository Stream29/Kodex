package io.github.stream29.kodex.openai.accountusage

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One atomically published account-usage aggregate.
 *
 * Mandatory rate-limit usage is available even when an optional section is
 * unavailable; the missing sections are named in [unavailableSections].
 * [fetchedAt] marks when this aggregate was assembled, not when each provider
 * field was originally measured.
 *
 * @property rateLimits Provider-supplied rate-limit statuses; may be empty
 * when no status rows were supplied.
 * @property resetCredits Available count and optional credit details.
 * @property tokenUsage Null when token activity is unavailable in this snapshot.
 * @property unavailableSections Optional sections whose fetch failed.
 */
@Serializable
public data class CodexAccountUsageSnapshot(
    public val rateLimits: List<CodexAccountRateLimit>,
    public val resetCredits: CodexRateLimitResetCredits,
    public val tokenUsage: CodexAccountTokenUsage? = null,
    public val unavailableSections: Set<CodexAccountUsageSection> = emptySet(),
    public val fetchedAt: Instant,
)

/** Optional usage sections whose failure does not invalidate the rate-limit snapshot. */
@Serializable
public enum class CodexAccountUsageSection {
    /** Detailed available reset-credit rows could not be loaded. */
    ResetCreditDetails,

    /** Account-wide token activity could not be loaded. */
    TokenUsage,
}

/**
 * Current status and windows for one backend-defined rate limit.
 * @property name Display name of this limit.
 * @property meteredFeature Backend key for the metered feature.
 * @property allowed Whether the backend currently allows use.
 * @property limitReached Whether the backend reports exhaustion.
 * @property primaryWindow Null when the backend did not supply a primary window.
 * @property secondaryWindow Null when the backend did not supply a secondary window.
 */
@Serializable
public data class CodexAccountRateLimit(
    public val name: String,
    public val meteredFeature: String,
    public val allowed: Boolean,
    public val limitReached: Boolean,
    public val primaryWindow: CodexAccountRateLimitWindow? = null,
    public val secondaryWindow: CodexAccountRateLimitWindow? = null,
)

/**
 * Usage and reset timing for one rate-limit window.
 *
 * @property usedPercent Backend-reported usage percentage.
 * @property durationSeconds Length of the metering window.
 * @property resetAfterSeconds Relative wait until reset, as reported by the backend.
 * @property resetsAt Absolute reset time from the backend.
 */
@Serializable
public data class CodexAccountRateLimitWindow(
    public val usedPercent: Long,
    public val durationSeconds: Long,
    public val resetAfterSeconds: Long,
    public val resetsAt: Instant,
)

/**
 * Available reset count and optional backend-provided detail rows.
 * @property availableCount Null when the available reset count is unknown, not zero.
 * @property credits Null when detail rows are unavailable, distinct from a known empty list.
 */
@Serializable
public data class CodexRateLimitResetCredits(
    public val availableCount: Long?,
    public val credits: List<CodexRateLimitResetCredit>? = null,
)

/**
 * One currently available Codex rate-limit reset credit.
 * @property id Backend identifier to select this credit for a reset attempt.
 * @property grantedAt Null when no usable grant timestamp was provided.
 * @property expiresAt Null when no usable expiry timestamp was provided.
 * @property title Null when no title was provided.
 * @property description Null when no description was provided.
 */
@Serializable
public data class CodexRateLimitResetCredit(
    public val id: String,
    public val grantedAt: Instant?,
    public val expiresAt: Instant?,
    public val title: String? = null,
    public val description: String? = null,
)

/**
 * Account-wide token activity and optional daily buckets.
 * @property lifetimeTokens Null when the lifetime token count is unavailable.
 * @property peakDailyTokens Null when the peak daily token count is unavailable.
 * @property longestRunningTurnSeconds Null when the longest turn duration is unavailable.
 * @property currentStreakDays Null when the current streak length is unavailable.
 * @property longestStreakDays Null when the longest streak length is unavailable.
 * @property dailyUsageBuckets Null when daily buckets are unavailable, not a known empty list.
 */
@Serializable
public data class CodexAccountTokenUsage(
    public val lifetimeTokens: Long? = null,
    public val peakDailyTokens: Long? = null,
    public val longestRunningTurnSeconds: Long? = null,
    public val currentStreakDays: Long? = null,
    public val longestStreakDays: Long? = null,
    public val dailyUsageBuckets: List<CodexAccountTokenUsageDailyBucket>? = null,
)

/**
 * Tokens used in one backend-defined calendar-day bucket.
 * @property startDate Backend-supplied key identifying the calendar day.
 * @property tokens Count attributed to that day.
 */
@Serializable
public data class CodexAccountTokenUsageDailyBucket(
    public val startDate: String,
    public val tokens: Long,
)

/** Definitive business outcome from consuming a reset credit. */
@Serializable
public enum class CodexRateLimitResetOutcome {
    /** The backend performed a reset. */
    Reset,

    /** The backend found no limit needing a reset. */
    NothingToReset,

    /** No usable credit was available for the request. */
    NoCredit,

    /** The backend reports that the requested reset was already redeemed. */
    AlreadyRedeemed,
}
