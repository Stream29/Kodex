package io.github.stream29.codex.lite.openai

/**
 * @property accountId Nullable because Codex auth files may omit the account id;
 * `null` means no account header should be sent.
 * @property planType Nullable because Codex auth files may omit the plan type
 * or provide a value this client does not recognize; `null` means no known
 * plan type is available.
 */
public data class OpenAiSubscriptionAuth(
    public val accessToken: String,
    public val accountId: String? = null,
    public val planType: OpenAiSubscriptionPlan? = null,
    public val isFedrampAccount: Boolean = false,
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

public fun interface OpenAiSubscriptionAuthProvider {
    public suspend fun currentAuth(): OpenAiSubscriptionAuth

    public suspend fun refreshAuth(): OpenAiSubscriptionAuth = currentAuth()
}
