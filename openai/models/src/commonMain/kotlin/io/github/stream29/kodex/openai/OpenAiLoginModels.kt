package io.github.stream29.kodex.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Parameters required to build an OpenAI OAuth authorization URL. */
public data class OpenAiLoginAuthorization(
    public val redirectUri: String,
    public val codeChallenge: String,
    public val state: String,
    public val allowedWorkspaceIds: List<String> = emptyList(),
) {
    init {
        require(redirectUri.isNotBlank()) { "redirectUri must not be blank." }
        require(codeChallenge.isNotBlank()) { "codeChallenge must not be blank." }
        require(state.isNotBlank()) { "state must not be blank." }
        require(allowedWorkspaceIds.all(String::isNotBlank)) {
            "allowedWorkspaceIds must not contain blank values."
        }
    }
}

/** Authorization-code and PKCE verifier received by the local login flow. */
public data class OpenAiAuthorizationCodeExchange(
    public val authorizationCode: String,
    public val redirectUri: String,
    public val codeVerifier: String,
) {
    init {
        require(authorizationCode.isNotBlank()) { "authorizationCode must not be blank." }
        require(redirectUri.isNotBlank()) { "redirectUri must not be blank." }
        require(codeVerifier.isNotBlank()) { "codeVerifier must not be blank." }
    }
}

/** Partial OAuth token rotation returned by the refresh-token endpoint. */
@Serializable
public data class OpenAiSubscriptionTokenRefresh(
    @SerialName("id_token")
    public val idToken: String? = null,
    @SerialName("access_token")
    public val accessToken: String? = null,
    @SerialName("refresh_token")
    public val refreshToken: String? = null,
)
