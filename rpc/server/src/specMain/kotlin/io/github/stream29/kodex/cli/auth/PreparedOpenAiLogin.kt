@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiLoginResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient

/** Local backend value, not serializable and never returned directly through RPC. */
public expect class PreparedOpenAiLogin {
    public val redirectUri: String
    public val state: String
    public val authorizationUrl: String
    public suspend fun exchangeCode(code: String): OpenAiLoginResult<OpenAiSubscriptionTokens>
}

/** Prepares backend PKCE state for the supplied frontend redirect URI; owns no listener. */
public expect fun prepareOpenAiLogin(client: OpenAiLoginClient, redirectUri: String): PreparedOpenAiLogin
