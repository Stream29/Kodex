package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens

/** OAuth transport for signing in to an OpenAI ChatGPT subscription. */
public interface OpenAiLoginClient : AutoCloseable {
    /** Builds the browser authorization URL for a locally managed PKCE flow. */
    public fun authorizationUrl(request: OpenAiLoginAuthorization): String

    /** Exchanges an authorization code and its PKCE verifier for complete subscription tokens. */
    public suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiSubscriptionTokens

    /** Refreshes a subscription token set and returns only the rotated fields. */
    public suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiSubscriptionTokenRefresh

    override fun close(): Unit = Unit
}

/** A non-successful response from the OpenAI OAuth token endpoint. */
public class OpenAiLoginException(
    message: String,
) : IllegalStateException(message)
