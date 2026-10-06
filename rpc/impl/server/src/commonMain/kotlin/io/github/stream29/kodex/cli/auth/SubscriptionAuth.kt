package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthMode
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class ActiveSubscriptionAuth(
    val tokens: OpenAiSubscriptionTokens,
    val lastRefresh: Instant,
) {
    val publicState: OpenAiSubscriptionAuthState
        get() {
            val claims = tokens.idToken.subscriptionJwtClaims()
            return OpenAiSubscriptionAuthState(
                accessToken = tokens.accessToken,
                accountId = tokens.accountId
                    ?.takeIf(String::isNotBlank)
                    ?: claims.accountId,
                planType = claims.planType,
                email = claims.email,
            )
        }

    fun refreshed(
        response: OpenAiSubscriptionTokenRefresh,
        refreshedAt: Instant,
    ): ActiveSubscriptionAuth = copy(
        tokens = tokens.copy(
            idToken = response.idToken ?: tokens.idToken,
            accessToken = response.accessToken ?: tokens.accessToken,
            refreshToken = response.refreshToken ?: tokens.refreshToken,
        ),
        lastRefresh = refreshedAt,
    )
}

internal sealed interface AuthLoadResult {
    data class Loaded(
        val auth: ActiveSubscriptionAuth,
    ) : AuthLoadResult

    data class Unavailable(
        val reason: OpenAiAuthState.Unavailable,
        val failure: Throwable? = null,
    ) : AuthLoadResult
}

internal fun CodexAuthJson.toAuthLoadResult(): AuthLoadResult {
    val mode = authMode ?: CodexAuthMode.Chatgpt
    if (mode != CodexAuthMode.Chatgpt && mode != CodexAuthMode.ChatgptAuthTokens) {
        return AuthLoadResult.Unavailable(
            OpenAiAuthState.Unavailable.UnsupportedAuthMode,
        )
    }
    val tokenData = tokens ?: return AuthLoadResult.Unavailable(
        OpenAiAuthState.Unavailable.InvalidCredentials,
    )
    return AuthLoadResult.Loaded(
        ActiveSubscriptionAuth(
            tokens = tokenData,
            lastRefresh = lastRefresh ?: Clock.System.now(),
        ),
    )
}

internal fun KodexAuthFile.toAuthLoadResult(): AuthLoadResult {
    if (authMode != CodexAuthMode.Chatgpt) {
        return AuthLoadResult.Unavailable(
            OpenAiAuthState.Unavailable.UnsupportedAuthMode,
        )
    }
    return AuthLoadResult.Loaded(
        ActiveSubscriptionAuth(
            tokens = tokens,
            lastRefresh = lastRefresh,
        ),
    )
}

internal fun subscriptionRefreshAt(
    tokens: OpenAiSubscriptionTokens,
    lastRefresh: Instant,
): Instant {
    val expiresAt = tokens.accessToken.subscriptionJwtClaims().expiresAt
    return expiresAt?.minus(RefreshWindow) ?: lastRefresh + RefreshFallbackInterval
}

private val RefreshWindow: Duration = 5.minutes
private val RefreshFallbackInterval: Duration = 8.days
