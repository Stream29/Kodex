package io.github.stream29.kodex.openai.client

import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokenRefresh
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as OpenAiLoginClientContract
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginException
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Creates an OpenAI OAuth client using the configured Codex-compatible defaults. */
public fun OpenAiLoginClient(): OpenAiLoginClientContract {
    val clientId = configuredOpenAiLoginClientId()
    val refreshTokenEndpoint = configuredOpenAiRefreshTokenEndpoint()
    return OpenAiLoginClientImpl(
        clientId = clientId,
        refreshTokenEndpoint = refreshTokenEndpoint,
        httpClient = createOpenAiLoginHttpClient(refreshTokenEndpoint),
    )
}

internal fun OpenAiLoginClient(engine: HttpClientEngine): OpenAiLoginClientContract =
    OpenAiLoginClientImpl(
        clientId = DefaultOpenAiLoginClientId,
        refreshTokenEndpoint = DefaultOpenAiTokenEndpoint,
        httpClient = HttpClient(engine) {
            configureOpenAiLoginClient(DefaultOpenAiTokenEndpoint)
        },
    )

private class OpenAiLoginClientImpl(
    private val clientId: String,
    private val refreshTokenEndpoint: String,
    private val httpClient: HttpClient,
) : OpenAiLoginClientContract {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        URLBuilder().apply {
            takeFrom(DefaultOpenAiAuthorizationEndpoint)
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", request.redirectUri)
            parameters.append("scope", OpenAiLoginScopes.joinToString(separator = " "))
            parameters.append("code_challenge", request.codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("id_token_add_organizations", "true")
            parameters.append("codex_cli_simplified_flow", "true")
            parameters.append("state", request.state)
            parameters.append("originator", DefaultOpenAiLoginOriginator)
            if (request.allowedWorkspaceIds.isNotEmpty()) {
                parameters.append(
                    "allowed_workspace_id",
                    request.allowedWorkspaceIds.joinToString(separator = ","),
                )
            }
        }.buildString()

    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiSubscriptionTokens =
        httpClient.post(DefaultOpenAiTokenEndpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", request.authorizationCode)
                        append("redirect_uri", request.redirectUri)
                        append("client_id", clientId)
                        append("code_verifier", request.codeVerifier)
                    },
                ),
            )
        }.decodeLoginResponse()

    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiSubscriptionTokenRefresh {
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank." }
        return httpClient.post(refreshTokenEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(
                OpenAiTokenRefreshRequest(
                    clientId = clientId,
                    refreshToken = refreshToken,
                ),
            )
        }.decodeLoginResponse()
    }

    override fun close() {
        httpClient.close()
    }
}

private suspend inline fun <reified T> HttpResponse.decodeLoginResponse(): T {
    if (!status.isSuccess()) {
        throw OpenAiLoginException(
            "OpenAI login request failed with HTTP ${status.value} (${status.description}).",
        )
    }
    return body()
}

private fun createOpenAiLoginHttpClient(refreshTokenEndpoint: String): HttpClient = HttpClient {
    configureOpenAiLoginClient(refreshTokenEndpoint)
}

private fun HttpClientConfig<*>.configureOpenAiLoginClient(refreshTokenEndpoint: String) {
    install(HttpRequestRetry) {
        maxRetries = 3
        retryIf { request, response ->
            request.url.toString() == refreshTokenEndpoint &&
                (response.status.value == 408 ||
                    response.status.value == 429 ||
                    response.status.value in 500..599)
        }
        retryOnExceptionIf { request, cause ->
            request.url.buildString() == refreshTokenEndpoint &&
                cause !is CancellationException
        }
        exponentialDelay()
    }
    install(ContentNegotiation) {
        json(OpenAiJsonCodec)
    }
}

private fun configuredOpenAiRefreshTokenEndpoint(): String =
    environmentVariable(CodexRefreshTokenUrlOverrideEnvironmentVariable)
        ?.takeIf(String::isNotBlank)
        ?: DefaultOpenAiTokenEndpoint

private fun configuredOpenAiLoginClientId(): String =
    environmentVariable(CodexLoginClientIdEnvironmentVariable)
        ?.takeIf(String::isNotBlank)
        ?: DefaultOpenAiLoginClientId

@Serializable
private data class OpenAiTokenRefreshRequest(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("grant_type")
    val grantType: String = "refresh_token",
    @SerialName("refresh_token")
    val refreshToken: String,
)

private val OpenAiLoginScopes: List<String> = listOf(
    "openid",
    "profile",
    "email",
    "offline_access",
    "api.connectors.read",
    "api.connectors.invoke",
)

private const val DefaultOpenAiAuthorizationEndpoint: String = "https://auth.openai.com/oauth/authorize"
private const val DefaultOpenAiTokenEndpoint: String = "https://auth.openai.com/oauth/token"
private const val DefaultOpenAiLoginClientId: String = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val DefaultOpenAiLoginOriginator: String = "codex_cli_rs"
private const val CodexRefreshTokenUrlOverrideEnvironmentVariable: String =
    "CODEX_REFRESH_TOKEN_URL_OVERRIDE"
private const val CodexLoginClientIdEnvironmentVariable: String =
    "CODEX_APP_SERVER_LOGIN_CLIENT_ID"
