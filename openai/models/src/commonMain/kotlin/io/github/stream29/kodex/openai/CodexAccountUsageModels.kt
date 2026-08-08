package io.github.stream29.kodex.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Account rate-limit payload returned by the ChatGPT Codex usage endpoint. */
@Serializable
public data class CodexAccountUsageResponse(
    @SerialName("plan_type")
    public val planType: String? = null,
    @SerialName("rate_limit")
    public val rateLimit: CodexAccountRateLimitStatus? = null,
    @SerialName("additional_rate_limits")
    public val additionalRateLimits: List<CodexAdditionalRateLimitStatus>? = null,
    @SerialName("rate_limit_reset_credits")
    public val rateLimitResetCredits: CodexRateLimitResetCreditsSummary? = null,
)

/** One primary or secondary rate-limit window from the Codex usage payload. */
@Serializable
public data class CodexRateLimitWindow(
    @SerialName("used_percent")
    public val usedPercent: Long,
    @SerialName("limit_window_seconds")
    public val limitWindowSeconds: Long,
    @SerialName("reset_after_seconds")
    public val resetAfterSeconds: Long,
    @SerialName("reset_at")
    public val resetAt: Long,
)

/** Current availability and windows for one Codex rate limit. */
@Serializable
public data class CodexAccountRateLimitStatus(
    public val allowed: Boolean,
    @SerialName("limit_reached")
    public val limitReached: Boolean,
    @SerialName("primary_window")
    public val primaryWindow: CodexRateLimitWindow? = null,
    @SerialName("secondary_window")
    public val secondaryWindow: CodexRateLimitWindow? = null,
)

/** An additional metered Codex rate limit advertised by the backend. */
@Serializable
public data class CodexAdditionalRateLimitStatus(
    @SerialName("limit_name")
    public val limitName: String,
    @SerialName("metered_feature")
    public val meteredFeature: String,
    @SerialName("rate_limit")
    public val rateLimit: CodexAccountRateLimitStatus? = null,
)

/** Count-only reset-credit information included with the usage payload. */
@Serializable
public data class CodexRateLimitResetCreditsSummary(
    @SerialName("available_count")
    public val availableCount: Long,
)

/** Detailed reset-credit payload returned by the reset-credit list endpoint. */
@Serializable
public data class CodexRateLimitResetCreditsResponse(
    public val credits: List<CodexRateLimitResetCredit> = emptyList(),
    @SerialName("available_count")
    public val availableCount: Long,
)

/** One backend-issued credit that may reset eligible Codex rate-limit windows. */
@Serializable
public data class CodexRateLimitResetCredit(
    public val id: String,
    @SerialName("reset_type")
    public val resetType: String,
    public val status: String,
    @SerialName("granted_at")
    public val grantedAt: String,
    @SerialName("expires_at")
    public val expiresAt: String? = null,
    public val title: String? = null,
    public val description: String? = null,
)

/** Idempotent request body for consuming one Codex rate-limit reset credit. */
@Serializable
public data class CodexRateLimitResetConsumeRequest(
    @SerialName("redeem_request_id")
    public val redeemRequestId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("credit_id")
    public val creditId: String? = null,
)

/** Backend result code for a reset-credit consume request. */
@Serializable
public enum class CodexRateLimitResetConsumeCode {
    @SerialName("reset")
    Reset,

    @SerialName("nothing_to_reset")
    NothingToReset,

    @SerialName("no_credit")
    NoCredit,

    @SerialName("already_redeemed")
    AlreadyRedeemed,
}

/** Response returned after attempting to consume a Codex rate-limit reset credit. */
@Serializable
public data class CodexRateLimitResetConsumeResponse(
    public val code: CodexRateLimitResetConsumeCode,
    @SerialName("windows_reset")
    public val windowsReset: Long = 0L,
)

/** Account-wide token activity returned by the Codex profile endpoint. */
@Serializable
public data class CodexTokenUsageProfile(
    public val stats: CodexTokenUsageProfileStats = CodexTokenUsageProfileStats(),
)

/** Summary and optional daily token activity for a Codex account. */
@Serializable
public data class CodexTokenUsageProfileStats(
    @SerialName("lifetime_tokens")
    public val lifetimeTokens: Long? = null,
    @SerialName("peak_daily_tokens")
    public val peakDailyTokens: Long? = null,
    @SerialName("longest_running_turn_sec")
    public val longestRunningTurnSeconds: Long? = null,
    @SerialName("current_streak_days")
    public val currentStreakDays: Long? = null,
    @SerialName("longest_streak_days")
    public val longestStreakDays: Long? = null,
    @SerialName("daily_usage_buckets")
    public val dailyUsageBuckets: List<CodexTokenUsageDailyBucket>? = null,
)

/** Tokens used during one backend-defined calendar day. */
@Serializable
public data class CodexTokenUsageDailyBucket(
    @SerialName("start_date")
    public val startDate: String,
    public val tokens: Long,
)
