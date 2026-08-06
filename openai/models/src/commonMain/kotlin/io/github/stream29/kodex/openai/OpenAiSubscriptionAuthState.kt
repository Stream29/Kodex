package io.github.stream29.kodex.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Complete OAuth tokens for a ChatGPT subscription session.
 *
 * @property idToken Raw JWT used to derive subscription claims.
 * @property accessToken Bearer JWT used for OpenAI API requests.
 * @property refreshToken Token used to renew the subscription session.
 * @property accountId Nullable because an OAuth response may omit an explicit
 * account id; `null` means it must be derived from [idToken] when needed.
 */
@Serializable
public data class OpenAiSubscriptionTokens(
    @SerialName("id_token")
    public val idToken: String,
    @SerialName("access_token")
    public val accessToken: String,
    @SerialName("refresh_token")
    public val refreshToken: String,
    @SerialName("account_id")
    public val accountId: String? = null,
)

/**
 * @property accountId Nullable because Codex auth files may omit the account id;
 * `null` means no account header should be sent.
 * @property planType Nullable because Codex auth files may omit the plan type
 * or provide a value this client does not recognize; `null` means no known
 * plan type is available.
 * @property email Nullable because an ID token may omit both supported email
 * claims; `null` means the authenticated account has no displayable email.
 */
public data class OpenAiSubscriptionAuthState(
    public val accessToken: String,
    public val accountId: String? = null,
    public val planType: OpenAiSubscriptionPlan? = null,
    public val email: String? = null,
)

public enum class OpenAiSubscriptionPlan(public val rawValue: String) {
    Free("free"),
    Go("go"),
    Plus("plus"),
    Pro("pro"),
    ProLite("prolite"),
    Team("team"),
    SelfServeBusinessUsageBased("self_serve_business_usage_based"),
    Business("business"),
    EnterpriseCbpUsageBased("enterprise_cbp_usage_based"),
    Enterprise("enterprise"),
    Edu("edu"),
    ;

    public companion object {
        /**
         * @return Nullable because the backend may introduce new plan strings;
         * `null` means the raw value is not currently recognized.
         */
        public fun fromRawValue(rawValue: String): OpenAiSubscriptionPlan? =
            when (val normalized = rawValue.lowercase()) {
                "hc" -> Enterprise
                "education" -> Edu
                else -> entries.firstOrNull { plan -> plan.rawValue == normalized }
            }
    }
}
