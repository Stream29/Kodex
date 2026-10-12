package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiLoginResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** OAuth transport for signing in to an OpenAI ChatGPT subscription. */
public interface OpenAiLoginClient : AutoCloseable {
    /** Builds the browser authorization URL for a locally managed PKCE flow. */
    public fun authorizationUrl(request: OpenAiLoginAuthorization): String

    /**
     * Exchanges an authorization code and its PKCE verifier for complete
     * subscription tokens. A non-successful HTTP response is returned as
     * [OpenAiLoginResult.Failure] so callers do not need to catch a provider
     * exception.
     *
     * @throws IllegalArgumentException if a field in [request] is blank.
     * @throws IOException if the request cannot obtain an HTTP response.
     * @throws SerializationException if a successful response cannot be
     * decoded as [OpenAiSubscriptionTokens].
     * @throws CancellationException if the caller's coroutine is cancelled.
     */
    public suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens>

    /**
     * Refreshes a subscription token set and returns only the rotated fields.
     * A non-successful HTTP response is returned as
     * [OpenAiLoginResult.Failure] so callers do not need to catch a provider
     * exception.
     *
     * @throws IllegalArgumentException if [refreshToken] is blank.
     * @throws IOException if the request cannot obtain an HTTP response.
     * @throws SerializationException if a successful response cannot be
     * decoded as [OpenAiSubscriptionTokenRefresh].
     * @throws CancellationException if the caller's coroutine is cancelled.
     */
    public suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh>

    /** Releases transport resources owned by this client, if any. */
    override fun close(): Unit = Unit
}
