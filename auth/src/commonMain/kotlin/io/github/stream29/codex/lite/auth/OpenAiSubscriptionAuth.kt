package io.github.stream29.codex.lite.auth

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
