@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiLoginResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.ktor.util.generateNonceBlocking

public actual class PreparedOpenAiLogin internal constructor(
    public actual val redirectUri: String,
    public actual val state: String,
    public actual val authorizationUrl: String,
    private val verifier: String,
    private val client: OpenAiLoginClient,
) {
    public actual suspend fun exchangeCode(code: String): OpenAiLoginResult<OpenAiSubscriptionTokens> =
        client.exchangeAuthorizationCode(OpenAiAuthorizationCodeExchange(code, redirectUri, verifier))
}

public actual fun prepareOpenAiLogin(client: OpenAiLoginClient, redirectUri: String): PreparedOpenAiLogin {
    val verifier = generateNonceBlocking(64)
    val state = generateNonceBlocking(32)
    return PreparedOpenAiLogin(
        redirectUri, state,
        client.authorizationUrl(OpenAiLoginAuthorization(redirectUri, pkceCodeChallenge(verifier), state)),
        verifier, client,
    )
}
