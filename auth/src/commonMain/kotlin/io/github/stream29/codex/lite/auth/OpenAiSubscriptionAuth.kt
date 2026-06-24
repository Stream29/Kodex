package io.github.stream29.codex.lite.auth

/**
 * @property accountId Nullable because Codex auth files may omit the account id;
 * `null` means no account header should be sent.
 * @property planType Nullable because Codex auth files may omit the plan type;
 * `null` means no plan-type value is available.
 */
public data class OpenAiSubscriptionAuth(
    public val accessToken: String,
    public val accountId: String? = null,
    public val planType: String? = null,
    public val isFedrampAccount: Boolean = false,
)

public fun interface OpenAiSubscriptionAuthProvider {
    public suspend fun currentAuth(): OpenAiSubscriptionAuth

    public suspend fun refreshAuth(): OpenAiSubscriptionAuth = currentAuth()
}
