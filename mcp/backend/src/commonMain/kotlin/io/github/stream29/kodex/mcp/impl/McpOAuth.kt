@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenEndpointAuthMethod
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.streamablehttp.McpStreamableHttpClient
import io.github.stream29.kodex.utils.oauthloopback.pkceS256CodeChallenge
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.util.generateNonceBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Shared OAuth browser-login preparation and token-refresh implementation for
 * Streamable HTTP servers.
 */
public actual class DefaultMcpOAuthClient internal constructor(
    private val httpClient: HttpClient,
) : McpOAuthLoginPreparer, McpOAuthTokenRefresher {
    /** Discovers/registers and prepares PKCE without opening a callback listener. */
    actual override suspend fun prepare(
        configuration: McpServerConfiguration.StreamableHttp,
    ): PreparedMcpOAuthLogin {
        val configuredOAuth = configuration.oauth as? McpOAuthConfiguration.Uninitialized
            ?: throw IllegalArgumentException(
                "An MCP OAuth login requires an uninitialized configuration.",
            )
        val challenge = discoverAuthorizationChallenge(configuration)
        val metadata = resolveMetadata(
            serverUrl = configuration.url,
            oauth = configuredOAuth,
            challenge = challenge,
        )
        val registeredClient = resolveClient(configuredOAuth.client, metadata)
        val oauth = configuredOAuth.copy(
            client = registeredClient.client,
            resource = metadata.resource,
            scopes = metadata.scopes,
        )
        val resolvedMetadata = metadata.copy(
            tokenEndpointAuthMethod = registeredClient.tokenEndpointAuthMethod,
        )
        val verifier = generateNonceBlocking(CodeVerifierLength)
        val state = generateNonceBlocking(StateLength)
        return PreparedMcpOAuthLogin(
            preparedConfiguration = oauth,
            state = state,
            authorizationUrl = authorizationUrl(
                endpoint = resolvedMetadata.authorizationEndpoint,
                oauth = oauth,
                verifier = verifier,
                state = state,
            ),
            exchange = { code -> exchangeMcpAuthorizationCode(httpClient, oauth, resolvedMetadata, verifier, code) },
        )
    }

    actual override suspend fun refresh(
        configuration: McpOAuthConfiguration.Initialized,
    ): McpOAuthConfiguration.Initialized {
        val refreshToken = configuration.refreshToken
            ?: throw IllegalStateException("The MCP OAuth token cannot be refreshed.")
        val response = httpClient.submitTokenRequest(
            endpoint = configuration.resolvedTokenEndpoint,
            method = configuration.tokenEndpointAuthMethod,
            clientId = requireNotNull(configuration.client.clientId) {
                "The initialized MCP OAuth client has no client id."
            },
            clientSecret = configuration.client.clientSecret,
        ) {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken.value)
            configuration.resource?.let { resource -> append("resource", resource) }
            if (configuration.scopes.isNotEmpty()) {
                append("scope", configuration.scopes.joinToString(" "))
            }
        }
        val tokens = response.requireTokenResponse()
        val tokenType = tokens.tokenType ?: configuration.tokenType
        tokenType.requireBearerTokenType()
        return configuration.copy(
            accessToken = McpSecret(tokens.accessToken),
            refreshToken = tokens.refreshToken?.let(::McpSecret) ?: refreshToken,
            tokenType = tokenType,
            expiresAtEpochSeconds = tokens.expiresInSeconds?.let { expiresIn ->
                (Clock.System.now() + expiresIn.seconds).epochSeconds
            },
        )
    }

    private suspend fun discoverAuthorizationChallenge(
        configuration: McpServerConfiguration.StreamableHttp,
    ): OAuthChallenge? =
        try {
            val response = httpClient.post(configuration.url) {
                headers {
                    configuration.headers.forEach { (name, value) -> set(name, value.value) }
                }
                header(
                    HttpHeaders.Accept,
                    "${ContentType.Application.Json}, ${ContentType.Text.EventStream}",
                )
                contentType(ContentType.Application.Json)
                setBody(OAuthChallengeRequest)
            }
            try {
                if (
                    response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.Forbidden
                ) {
                    response.oauthChallenge()
                } else {
                    null
                }
            } finally {
                response.bodyAsChannel().cancel(
                    CancellationException("MCP OAuth challenge response is no longer needed."),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private suspend fun resolveMetadata(
        serverUrl: String,
        oauth: McpOAuthConfiguration.Uninitialized,
        challenge: OAuthChallenge?,
    ): ResolvedOAuthMetadata {
        val explicitAuthorization = oauth.client.authorizationEndpoint
        val explicitToken = oauth.client.tokenEndpoint
        val resourceMetadata = discoverResourceMetadata(
            serverUrl = serverUrl,
            advertisedUrl = challenge?.resourceMetadataUrl,
        )
        val resourceMetadataDocument = resourceMetadata?.document
        val discoveredResource = resourceMetadata?.resource
        discoveredResource?.requireMatchingResource(serverUrl)
        oauth.resource?.requireMatchingResource(serverUrl)
        val resource = oauth.resource ?: discoveredResource ?: serverUrl.canonicalResource()
        val authorizationServer = resourceMetadataDocument
            ?.get("authorization_servers")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonPrimitive
            ?.content
        val requiresAuthorizationMetadata =
            explicitAuthorization == null ||
                explicitToken == null ||
                oauth.client.clientId == null
        val authorizationMetadata = authorizationServer
            ?.let { server -> discoverAuthorizationMetadata(server) }
            ?: if (requiresAuthorizationMetadata) {
                discoverAuthorizationMetadata(serverUrl.origin())
            } else {
                null
            }
        val authorizationEndpoint = explicitAuthorization
            ?: authorizationMetadata?.requiredString("authorization_endpoint")
            ?: throw IllegalStateException(
                "MCP OAuth metadata is missing 'authorization_endpoint'.",
            )
        val tokenEndpoint = explicitToken
            ?: authorizationMetadata?.requiredString("token_endpoint")
            ?: throw IllegalStateException("MCP OAuth metadata is missing 'token_endpoint'.")
        if (authorizationMetadata != null) {
            val codeChallengeMethods = authorizationMetadata
                .get("code_challenge_methods_supported")
                ?.jsonArray
                ?.map { element -> element.jsonPrimitive.content }
                .orEmpty()
            require("S256" in codeChallengeMethods) {
                "The MCP OAuth authorization server does not advertise PKCE S256 support."
            }
        }
        val methods = when (authorizationMetadata) {
            null -> emptyList()
            else -> authorizationMetadata
                .get("token_endpoint_auth_methods_supported")
                ?.jsonArray
                ?.map { element -> element.jsonPrimitive.content }
                ?: listOf("client_secret_basic")
        }
        val scopes = oauth.scopes.ifEmpty {
            challenge?.scopes
                ?.takeIf(List<String>::isNotEmpty)
                ?: resourceMetadataDocument
                    ?.get("scopes_supported")
                    ?.jsonArray
                    ?.map { element -> element.jsonPrimitive.content }
                    .orEmpty()
        }
        return ResolvedOAuthMetadata(
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            tokenEndpointAuthMethods = methods,
            registrationEndpoint = authorizationMetadata
                ?.get("registration_endpoint")
                ?.jsonPrimitive
                ?.content
                ?.takeIf(String::isNotBlank),
            resource = resource,
            scopes = scopes,
        )
    }

    private suspend fun discoverResourceMetadata(
        serverUrl: String,
        advertisedUrl: String?,
    ): ProtectedResourceMetadata? {
        if (advertisedUrl != null) {
            val document = getJsonOrNull(advertisedUrl)
                ?: throw IllegalStateException(
                    "MCP OAuth protected-resource discovery failed.",
                )
            return document.toProtectedResourceMetadata(serverUrl.canonicalResource())
        }
        val url = Url(serverUrl)
        val origin = serverUrl.origin()
        val path = url.encodedPath.takeUnless { it == "/" }.orEmpty()
        val query = url.encodedQuery
            .takeIf(String::isNotEmpty)
            ?.let { encoded -> "?$encoded" }
            .orEmpty()
        val candidates = listOf(
            "$origin/.well-known/oauth-protected-resource$path$query" to
                serverUrl.canonicalResource(),
            "$origin/.well-known/oauth-protected-resource" to origin,
        ).distinct()
        for ((candidate, expectedResource) in candidates) {
            getJsonOrNull(candidate)?.let { document ->
                return document.toProtectedResourceMetadata(expectedResource)
            }
        }
        return null
    }

    private suspend fun discoverAuthorizationMetadata(server: String): JsonObject? {
        val normalized = server.trimEnd('/')
        val url = Url(normalized)
        val origin = normalized.origin()
        val path = url.encodedPath.takeUnless { it == "/" }.orEmpty()
        val candidates = listOf(
            "$origin/.well-known/oauth-authorization-server$path",
            "$origin/.well-known/openid-configuration$path",
            "$normalized/.well-known/openid-configuration",
        ).distinct()
        return candidates.firstNotNullOfOrNull { candidate ->
            getJsonOrNull(candidate)?.also { metadata ->
                metadata.requiredString("issuer").requireSameOAuthIssuer(normalized)
            }
        }
    }

    private suspend fun getJsonOrNull(url: String): JsonObject? {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(
                CancellationException("MCP OAuth discovery response is no longer needed."),
            )
            return null
        }
        return try {
            OAuthJson.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolveClient(
        client: McpOAuthClient,
        metadata: ResolvedOAuthMetadata,
    ): RegisteredOAuthClient {
        if (client.clientId != null) {
            return RegisteredOAuthClient(
                client = client,
                tokenEndpointAuthMethod = selectTokenAuthMethod(
                    advertised = metadata.tokenEndpointAuthMethods,
                    hasClientSecret = client.clientSecret != null,
                ),
            )
        }
        val registrationEndpoint = metadata.registrationEndpoint
            ?: throw IllegalStateException(
                "The MCP OAuth server requires a pre-registered client id.",
            )
        val requestedMethod = selectRegistrationTokenAuthMethod(
            metadata.tokenEndpointAuthMethods,
        )
        val body = buildJsonObject {
            put("client_name", "Kodex")
            put("redirect_uris", buildJsonArray { add(JsonPrimitive(client.redirectUri)) })
            put("token_endpoint_auth_method", requestedMethod.serializedName)
        }
        val response = httpClient.post(registrationEndpoint) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(
                CancellationException("MCP OAuth registration response is no longer needed."),
            )
            throw IllegalStateException("The MCP OAuth client registration was rejected.")
        }
        val registered = OAuthJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val registeredClientId = registered.requiredString("client_id")
        val registeredSecret = registered["client_secret"]
            ?.jsonPrimitive
            ?.content
            ?.let(::McpSecret)
        val registeredMethod = registered["token_endpoint_auth_method"]
            ?.jsonPrimitive
            ?.content
            ?.let(::tokenEndpointAuthMethod)
            ?: requestedMethod
        require(
            metadata.tokenEndpointAuthMethods.isEmpty() ||
                registeredMethod.serializedName in metadata.tokenEndpointAuthMethods,
        ) {
            "The MCP OAuth registration returned an unadvertised client authentication method."
        }
        require(
            registeredMethod == McpOAuthTokenEndpointAuthMethod.None ||
                registeredSecret != null,
        ) {
            "The MCP OAuth registration omitted its required client secret."
        }
        return RegisteredOAuthClient(
            client = client.copy(
                clientId = registeredClientId,
                clientSecret = registeredSecret,
            ),
            tokenEndpointAuthMethod = registeredMethod,
        )
    }
}

/** Creates one OAuth client whose HTTP lifetime follows this scope. */
public actual fun CoroutineScope.DefaultMcpOAuthClient(): DefaultMcpOAuthClient =
    DefaultMcpOAuthClient(
        httpClient = McpStreamableHttpClient(),
    )

/** Backend-private PKCE material remains in the exchange closure, never in RPC models. */
public actual class PreparedMcpOAuthLogin actual constructor(
    public actual val preparedConfiguration: McpOAuthConfiguration.Uninitialized,
    public actual val state: String,
    public actual val authorizationUrl: String,
    private val exchange: suspend (String) -> McpOAuthConfiguration.Initialized,
) {
    public actual suspend fun exchangeCode(code: String): McpOAuthConfiguration.Initialized = exchange(code)
}

private suspend fun exchangeMcpAuthorizationCode(
    httpClient: HttpClient,
    oauth: McpOAuthConfiguration.Uninitialized,
    metadata: ResolvedOAuthMetadata,
    verifier: String,
    code: String,
): McpOAuthConfiguration.Initialized {
    val response = httpClient.submitTokenRequest(
        endpoint = metadata.tokenEndpoint,
        method = metadata.tokenEndpointAuthMethod,
        clientId = requireNotNull(oauth.client.clientId) {
            "The prepared MCP OAuth client has no client id."
        },
        clientSecret = oauth.client.clientSecret,
    ) {
        append("grant_type", "authorization_code")
        append("code", code)
        append("redirect_uri", oauth.client.redirectUri)
        append("code_verifier", verifier)
        append("resource", requireNotNull(oauth.resource))
    }
    val tokens = response.requireTokenResponse()
    val tokenType = tokens.tokenType ?: "Bearer"
    tokenType.requireBearerTokenType()
    return McpOAuthConfiguration.Initialized(
        client = oauth.client,
        resource = oauth.resource,
        scopes = oauth.scopes,
        resolvedAuthorizationEndpoint = metadata.authorizationEndpoint,
        resolvedTokenEndpoint = metadata.tokenEndpoint,
        tokenEndpointAuthMethod = metadata.tokenEndpointAuthMethod,
        accessToken = McpSecret(tokens.accessToken),
        refreshToken = tokens.refreshToken?.let(::McpSecret),
        tokenType = tokenType,
        expiresAtEpochSeconds = tokens.expiresInSeconds?.let { expiresIn ->
            (Clock.System.now() + expiresIn.seconds).epochSeconds
        },
    )
}

private data class ResolvedOAuthMetadata(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val tokenEndpointAuthMethods: List<String>,
    val registrationEndpoint: String?,
    val resource: String,
    val scopes: List<String>,
    val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod =
        McpOAuthTokenEndpointAuthMethod.None,
)

private data class ProtectedResourceMetadata(
    val document: JsonObject,
    val resource: String,
)

private data class RegisteredOAuthClient(
    val client: McpOAuthClient,
    val tokenEndpointAuthMethod: McpOAuthTokenEndpointAuthMethod,
)

private data class OAuthChallenge(
    val resourceMetadataUrl: String?,
    val scopes: List<String>,
)

private data class OAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String?,
    val expiresInSeconds: Long?,
)

private fun authorizationUrl(
    endpoint: String,
    oauth: McpOAuthConfiguration.Uninitialized,
    verifier: String,
    state: String,
): String =
    URLBuilder(endpoint).apply {
        parameters.append("response_type", "code")
        parameters.append(
            "client_id",
            requireNotNull(oauth.client.clientId) {
                "The prepared MCP OAuth client has no client id."
            },
        )
        parameters.append("redirect_uri", oauth.client.redirectUri)
        parameters.append("code_challenge", pkceS256CodeChallenge(verifier))
        parameters.append("code_challenge_method", "S256")
        parameters.append("state", state)
        if (oauth.scopes.isNotEmpty()) {
            parameters.append("scope", oauth.scopes.joinToString(" "))
        }
        parameters.append("resource", requireNotNull(oauth.resource))
    }.buildString()

private suspend fun HttpClient.submitTokenRequest(
    endpoint: String,
    method: McpOAuthTokenEndpointAuthMethod,
    clientId: String,
    clientSecret: McpSecret?,
    parameters: ParametersBuilder.() -> Unit,
): HttpResponse {
    val values = Parameters.build {
        parameters()
        when (method) {
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic -> Unit
            McpOAuthTokenEndpointAuthMethod.ClientSecretPost -> {
                append("client_id", clientId)
                clientSecret?.let { secret -> append("client_secret", secret.value) }
            }

            McpOAuthTokenEndpointAuthMethod.None -> append("client_id", clientId)
        }
    }
    return submitForm(endpoint, values) {
        if (method == McpOAuthTokenEndpointAuthMethod.ClientSecretBasic) {
            val secret = clientSecret?.value.orEmpty()
            header(HttpHeaders.Authorization, basicAuthorization(clientId, secret))
        }
    }
}

private suspend fun HttpResponse.requireTokenResponse(): OAuthTokenResponse {
    if (!status.isSuccess()) {
        bodyAsChannel().cancel(
            CancellationException("MCP OAuth token response is no longer needed."),
        )
        throw IllegalStateException("The MCP OAuth token endpoint rejected the request.")
    }
    val body = OAuthJson.parseToJsonElement(bodyAsText()).jsonObject
    body["error"]?.let {
        throw IllegalStateException("The MCP OAuth token endpoint returned an error.")
    }
    return OAuthTokenResponse(
        accessToken = body.requiredString("access_token"),
        refreshToken = body["refresh_token"]?.jsonPrimitive?.content,
        tokenType = body["token_type"]?.jsonPrimitive?.content,
        expiresInSeconds = body["expires_in"]?.jsonPrimitive?.longOrNull,
    )
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalStateException("MCP OAuth metadata is missing '$name'.")

private fun HttpResponse.oauthChallenge(): OAuthChallenge? =
    headers
        .getAll(HttpHeaders.WWWAuthenticate)
        .orEmpty()
        .flatMap(String::splitAuthorizationChallenges)
        .firstNotNullOfOrNull { value ->
            val parsed = runCatching { parseAuthorizationHeader(value) }.getOrNull()
                as? HttpAuthHeader.Parameterized
            parsed
                ?.takeIf { header -> header.authScheme.equals("Bearer", ignoreCase = true) }
                ?.parameters
                ?.associate { parameter -> parameter.name.lowercase() to parameter.value }
                ?.let { parameters ->
                    OAuthChallenge(
                        resourceMetadataUrl = parameters["resource_metadata"],
                        scopes = parameters["scope"]
                            ?.split(Whitespace)
                            ?.filter(String::isNotEmpty)
                            .orEmpty(),
                    )
                }
        }

private fun String.splitAuthorizationChallenges(): List<String> {
    val challenges = mutableListOf<String>()
    var start = 0
    var quoted = false
    var escaped = false
    forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            quoted && character == '\\' -> escaped = true
            character == '"' -> quoted = !quoted
            character == ',' && !quoted -> {
                val next = skipWhitespace(index + 1)
                val tokenEnd = skipToken(next)
                val afterToken = skipWhitespace(tokenEnd)
                val beginsChallenge =
                    tokenEnd > next && getOrNull(afterToken) != '='
                if (beginsChallenge) {
                    substring(start, index).trim()
                        .takeIf(String::isNotEmpty)
                        ?.let(challenges::add)
                    start = next
                }
            }
        }
    }
    substring(start).trim()
        .takeIf(String::isNotEmpty)
        ?.let(challenges::add)
    return challenges
}

private fun String.skipWhitespace(startIndex: Int): Int {
    var index = startIndex
    while (getOrNull(index)?.isWhitespace() == true) index += 1
    return index
}

private fun String.skipToken(startIndex: Int): Int {
    var index = startIndex
    while (getOrNull(index)?.isAuthorizationTokenCharacter() == true) index += 1
    return index
}

private fun Char.isAuthorizationTokenCharacter(): Boolean =
    isLetterOrDigit() || this in AuthorizationTokenPunctuation

private fun selectTokenAuthMethod(
    advertised: List<String>,
    hasClientSecret: Boolean,
): McpOAuthTokenEndpointAuthMethod {
    val candidates = advertised.ifEmpty {
        if (hasClientSecret) listOf("client_secret_post") else listOf("none")
    }
    return when {
        hasClientSecret && "client_secret_basic" in candidates ->
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic

        hasClientSecret && "client_secret_post" in candidates ->
            McpOAuthTokenEndpointAuthMethod.ClientSecretPost

        "none" in candidates -> McpOAuthTokenEndpointAuthMethod.None
        else -> throw IllegalStateException(
            "The MCP OAuth token endpoint has no supported client authentication method.",
        )
    }
}

private fun selectRegistrationTokenAuthMethod(
    advertised: List<String>,
): McpOAuthTokenEndpointAuthMethod {
    val candidates = advertised.ifEmpty { listOf("none") }
    return when {
        "none" in candidates -> McpOAuthTokenEndpointAuthMethod.None
        "client_secret_basic" in candidates ->
            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic

        "client_secret_post" in candidates ->
            McpOAuthTokenEndpointAuthMethod.ClientSecretPost

        else -> throw IllegalStateException(
            "The MCP OAuth server has no supported dynamic client authentication method.",
        )
    }
}

private fun tokenEndpointAuthMethod(value: String): McpOAuthTokenEndpointAuthMethod =
    when (value) {
        "client_secret_basic" -> McpOAuthTokenEndpointAuthMethod.ClientSecretBasic
        "client_secret_post" -> McpOAuthTokenEndpointAuthMethod.ClientSecretPost
        "none" -> McpOAuthTokenEndpointAuthMethod.None
        else -> throw IllegalStateException(
            "The MCP OAuth registration returned an unsupported client authentication method.",
        )
    }

private val McpOAuthTokenEndpointAuthMethod.serializedName: String
    get() = when (this) {
        McpOAuthTokenEndpointAuthMethod.ClientSecretBasic -> "client_secret_basic"
        McpOAuthTokenEndpointAuthMethod.ClientSecretPost -> "client_secret_post"
        McpOAuthTokenEndpointAuthMethod.None -> "none"
    }

private fun String.requireBearerTokenType() {
    require(equals("Bearer", ignoreCase = true)) {
        "The MCP OAuth token endpoint returned an unsupported token type."
    }
}

private fun String.requireMatchingResource(serverUrl: String) {
    val resource = Url(this)
    val server = Url(serverUrl)
    val resourcePath = resource.encodedPath.trimEnd('/')
    val serverPath = server.encodedPath.trimEnd('/')
    require(
        resource.protocol.name.equals(server.protocol.name, ignoreCase = true) &&
            resource.host.equals(server.host, ignoreCase = true) &&
            resource.port == server.port &&
            resource.fragment.isEmpty() &&
            (serverPath == resourcePath || serverPath.startsWith("$resourcePath/")),
    ) {
        "The MCP OAuth protected resource does not match the configured server."
    }
}

private fun JsonObject.toProtectedResourceMetadata(
    expectedResource: String,
): ProtectedResourceMetadata {
    val resource = requiredString("resource")
    require(resource.canonicalResource() == expectedResource.canonicalResource()) {
        "The MCP OAuth protected-resource metadata does not match its resource."
    }
    return ProtectedResourceMetadata(document = this, resource = resource)
}

private fun String.requireSameOAuthIssuer(expectedIssuer: String) {
    require(canonicalResource() == expectedIssuer.canonicalResource()) {
        "The MCP OAuth authorization-server metadata has an unexpected issuer."
    }
}

private fun String.canonicalResource(): String {
    val url = Url(this)
    require(url.fragment.isEmpty()) {
        "An MCP OAuth resource identifier must not contain a fragment."
    }
    val path = url.encodedPath.trimEnd('/').takeUnless { it == "/" }.orEmpty()
    val query = url.encodedQuery
        .takeIf(String::isNotEmpty)
        ?.let { encoded -> "?$encoded" }
        .orEmpty()
    return "${origin()}$path$query"
}

private fun String.origin(): String {
    val url = Url(this)
    val defaultPort = url.protocol.defaultPort
    val port = if (url.port == defaultPort) "" else ":${url.port}"
    val host = if (':' in url.host && !url.host.startsWith('[')) {
        "[${url.host}]"
    } else {
        url.host
    }
    return "${url.protocol.name}://$host$port"
}

private fun basicAuthorization(clientId: String, clientSecret: String): String {
    val encodedClientId = clientId.encodeURLParameter(spaceToPlus = true)
    val encodedClientSecret = clientSecret.encodeURLParameter(spaceToPlus = true)
    return "Basic ${Base64.encode("$encodedClientId:$encodedClientSecret".encodeToByteArray())}"
}

private val OAuthJson = Json { ignoreUnknownKeys = true }

private val Whitespace = Regex("\\s+")
private const val AuthorizationTokenPunctuation: String = "!#$%&'*+-.^_`|~"
private val OAuthChallengeRequest: String =
    """
    {
      "jsonrpc": "2.0",
      "id": "kodex-oauth-discovery",
      "method": "initialize",
      "params": {
        "protocolVersion": "2025-11-25",
        "capabilities": {},
        "clientInfo": {"name": "Kodex", "version": "oauth-discovery"}
      }
    }
    """.trimIndent()
private const val StateLength: Int = 64
private const val CodeVerifierLength: Int = 96
