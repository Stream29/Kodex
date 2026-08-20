package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttempt
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttemptFactory
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenEndpointAuthMethod
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenRefresher
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.streamablehttp.McpStreamableHttpClient
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
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.generateNonceBlocking
import kotlinx.coroutines.CompletableDeferred
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
 * Shared OAuth browser-login and token-refresh implementation for Streamable
 * HTTP servers.
 */
public class DefaultMcpOAuthClient internal constructor(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
) : McpOAuthLoginAttemptFactory, McpOAuthTokenRefresher {
    override suspend fun create(
        configuration: McpServerConfiguration.StreamableHttp,
    ): McpOAuthLoginAttempt {
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
        val callback = McpOAuthCallback.start(
            scope = scope,
            redirectUri = oauth.client.redirectUri,
            expectedState = state,
        )
        return try {
            DefaultMcpOAuthLoginAttempt(
                callback = callback,
                httpClient = httpClient,
                oauth = oauth,
                metadata = resolvedMetadata,
                verifier = verifier,
                preparedConfiguration = oauth,
                authorizationUrl = authorizationUrl(
                    endpoint = resolvedMetadata.authorizationEndpoint,
                    oauth = oauth,
                    verifier = verifier,
                    state = state,
                ),
            )
        } catch (failure: Throwable) {
            callback.close()
            throw failure
        }
    }

    override suspend fun refresh(
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
            put(
                "grant_types",
                buildJsonArray {
                    add(JsonPrimitive("authorization_code"))
                    add(JsonPrimitive("refresh_token"))
                },
            )
            put("response_types", buildJsonArray { add(JsonPrimitive("code")) })
            put("token_endpoint_auth_method", requestedMethod.serializedName)
            if (metadata.scopes.isNotEmpty()) {
                put("scope", metadata.scopes.joinToString(" "))
            }
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
public fun CoroutineScope.DefaultMcpOAuthClient(): DefaultMcpOAuthClient =
    DefaultMcpOAuthClient(
        scope = this,
        httpClient = McpStreamableHttpClient(),
    )

private class DefaultMcpOAuthLoginAttempt(
    private val callback: McpOAuthCallback,
    private val httpClient: HttpClient,
    private val oauth: McpOAuthConfiguration.Uninitialized,
    private val metadata: ResolvedOAuthMetadata,
    private val verifier: String,
    override val preparedConfiguration: McpOAuthConfiguration.Uninitialized,
    override val authorizationUrl: String,
) : McpOAuthLoginAttempt {
    override suspend fun awaitInitialized(): McpOAuthConfiguration.Initialized {
        try {
            val code = callback.awaitCode()
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
        } finally {
            callback.close()
        }
    }

    override fun close() {
        callback.close()
    }
}

private class McpOAuthCallback private constructor(
    private val server: EmbeddedServer<*, *>,
    private val completion: CompletableDeferred<CallbackParameters>,
) {
    suspend fun awaitCode(): String {
        val result = completion.await()
        result.error?.let {
            throw IllegalStateException("MCP OAuth authorization was not completed.")
        }
        return result.code
            ?: throw IllegalStateException("The MCP OAuth callback did not contain a code.")
    }

    fun close() {
        completion.cancel()
        server.stop()
    }

    companion object {
        suspend fun start(
            scope: CoroutineScope,
            redirectUri: String,
            expectedState: String,
        ): McpOAuthCallback {
            val redirect = Url(redirectUri)
            require(redirect.protocol.name == "http") {
                "An MCP OAuth loopback redirect must use HTTP."
            }
            require(redirect.host == "127.0.0.1" || redirect.host == "localhost") {
                "An MCP OAuth redirect must use a loopback host."
            }
            require(redirect.port > 0) { "An MCP OAuth redirect must specify a port." }
            val path = redirect.encodedPath
            val completion = CompletableDeferred<CallbackParameters>()
            val server = scope.embeddedServer(
                factory = CIO,
                host = redirect.host,
                port = redirect.port,
            ) {
                routing {
                    get(path) {
                        val parameters = CallbackParameters(
                            state = call.request.queryParameters["state"],
                            code = call.request.queryParameters["code"],
                            error = call.request.queryParameters["error"],
                        )
                        val accepted = parameters.state == expectedState &&
                            (parameters.code != null || parameters.error != null)
                        if (accepted) completion.complete(parameters)
                        call.respondText(
                            text = if (accepted) {
                                "Authorization received. You can return to Kodex."
                            } else {
                                "The authorization response was incomplete."
                            },
                            status = if (accepted) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                        )
                    }
                }
            }
            return try {
                server.startSuspend(wait = false)
                McpOAuthCallback(server, completion)
            } catch (failure: Throwable) {
                server.stop()
                throw failure
            }
        }
    }
}

private data class CallbackParameters(
    val state: String?,
    val code: String?,
    val error: String?,
)

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
        parameters.append("code_challenge", pkceCodeChallenge(verifier))
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

private fun pkceCodeChallenge(codeVerifier: String): String =
    Base64.UrlSafe.encode(sha256(codeVerifier.encodeToByteArray())).trimEnd('=')

private fun sha256(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8L
    val paddingLength = ((56 - (input.size + 1) % Sha256BlockSize) + Sha256BlockSize) %
        Sha256BlockSize
    val padded = ByteArray(input.size + 1 + paddingLength + Sha256LengthFieldSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until Sha256LengthFieldSize) {
        padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
    }

    var a = 0x6a09e667
    var b = 0xbb67ae85.toInt()
    var c = 0x3c6ef372
    var d = 0xa54ff53a.toInt()
    var e = 0x510e527f
    var f = 0x9b05688c.toInt()
    var g = 0x1f83d9ab
    var h = 0x5be0cd19
    val words = IntArray(Sha256WordCount)

    padded.asList().chunked(Sha256BlockSize).forEach { block ->
        for (index in 0 until 16) {
            val offset = index * 4
            words[index] = ((block[offset].toInt() and 0xff) shl 24) or
                ((block[offset + 1].toInt() and 0xff) shl 16) or
                ((block[offset + 2].toInt() and 0xff) shl 8) or
                (block[offset + 3].toInt() and 0xff)
        }
        for (index in 16 until Sha256WordCount) {
            val smallSigma0 = words[index - 15].rotateRight(7) xor
                words[index - 15].rotateRight(18) xor (words[index - 15] ushr 3)
            val smallSigma1 = words[index - 2].rotateRight(17) xor
                words[index - 2].rotateRight(19) xor (words[index - 2] ushr 10)
            words[index] = words[index - 16] + smallSigma0 + words[index - 7] + smallSigma1
        }

        var workingA = a
        var workingB = b
        var workingC = c
        var workingD = d
        var workingE = e
        var workingF = f
        var workingG = g
        var workingH = h
        for (index in 0 until Sha256WordCount) {
            val bigSigma1 =
                workingE.rotateRight(6) xor workingE.rotateRight(11) xor workingE.rotateRight(25)
            val choose = (workingE and workingF) xor (workingE.inv() and workingG)
            val temporary1 =
                workingH + bigSigma1 + choose + Sha256RoundConstants[index] + words[index]
            val bigSigma0 =
                workingA.rotateRight(2) xor workingA.rotateRight(13) xor workingA.rotateRight(22)
            val majority =
                (workingA and workingB) xor (workingA and workingC) xor (workingB and workingC)
            val temporary2 = bigSigma0 + majority

            workingH = workingG
            workingG = workingF
            workingF = workingE
            workingE = workingD + temporary1
            workingD = workingC
            workingC = workingB
            workingB = workingA
            workingA = temporary1 + temporary2
        }

        a += workingA
        b += workingB
        c += workingC
        d += workingD
        e += workingE
        f += workingF
        g += workingG
        h += workingH
    }

    return intArrayOf(a, b, c, d, e, f, g, h).flatMap { value ->
        listOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }.toByteArray()
}

private fun Int.rotateRight(bitCount: Int): Int =
    (this ushr bitCount) or (this shl (Int.SIZE_BITS - bitCount))

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
private const val Sha256BlockSize: Int = 64
private const val Sha256LengthFieldSize: Int = 8
private const val Sha256WordCount: Int = 64

private val Sha256RoundConstants: IntArray = intArrayOf(
    0x428a2f98,
    0x71374491,
    0xb5c0fbcf.toInt(),
    0xe9b5dba5.toInt(),
    0x3956c25b,
    0x59f111f1,
    0x923f82a4.toInt(),
    0xab1c5ed5.toInt(),
    0xd807aa98.toInt(),
    0x12835b01,
    0x243185be,
    0x550c7dc3,
    0x72be5d74,
    0x80deb1fe.toInt(),
    0x9bdc06a7.toInt(),
    0xc19bf174.toInt(),
    0xe49b69c1.toInt(),
    0xefbe4786.toInt(),
    0x0fc19dc6,
    0x240ca1cc,
    0x2de92c6f,
    0x4a7484aa,
    0x5cb0a9dc,
    0x76f988da,
    0x983e5152.toInt(),
    0xa831c66d.toInt(),
    0xb00327c8.toInt(),
    0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(),
    0xd5a79147.toInt(),
    0x06ca6351,
    0x14292967,
    0x27b70a85,
    0x2e1b2138,
    0x4d2c6dfc,
    0x53380d13,
    0x650a7354,
    0x766a0abb,
    0x81c2c92e.toInt(),
    0x92722c85.toInt(),
    0xa2bfe8a1.toInt(),
    0xa81a664b.toInt(),
    0xc24b8b70.toInt(),
    0xc76c51a3.toInt(),
    0xd192e819.toInt(),
    0xd6990624.toInt(),
    0xf40e3585.toInt(),
    0x106aa070,
    0x19a4c116,
    0x1e376c08,
    0x2748774c,
    0x34b0bcb5,
    0x391c0cb3,
    0x4ed8aa4a,
    0x5b9cca4f,
    0x682e6ff3,
    0x748f82ee,
    0x78a5636f,
    0x84c87814.toInt(),
    0x8cc70208.toInt(),
    0x90befffa.toInt(),
    0xa4506ceb.toInt(),
    0xbef9a3f7.toInt(),
    0xc67178f2.toInt(),
)
