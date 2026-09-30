package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiLoginResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.ktor.util.generateNonceBlocking

/** Local backend value, not serializable and never returned directly through RPC. */
public class PreparedOpenAiLogin internal constructor(
    public val redirectUri: String,
    public val state: String,
    public val authorizationUrl: String,
    private val verifier: String,
    private val client: OpenAiLoginClient,
) {
    public suspend fun exchangeCode(code: String): OpenAiLoginResult<OpenAiSubscriptionTokens> =
        client.exchangeAuthorizationCode(OpenAiAuthorizationCodeExchange(code, redirectUri, verifier))
}

/** Uses the same PKCE implementation as legacy local login, but owns no listener. */
public fun prepareOpenAiLogin(client: OpenAiLoginClient, redirectUri: String): PreparedOpenAiLogin {
    val verifier = generateNonceBlocking(64)
    val state = generateNonceBlocking(32)
    return PreparedOpenAiLogin(
        redirectUri, state,
        client.authorizationUrl(OpenAiLoginAuthorization(redirectUri, pkceCodeChallenge(verifier), state)),
        verifier, client,
    )
}
