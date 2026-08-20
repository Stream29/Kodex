package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.streamablehttp.McpStreamableHttpClient
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val mcpOAuthIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("real loopback OAuth login exchanges and refreshes tokens") {
        val fixture = McpOAuthFixture().start()
        val callbackPort = reserveLoopbackPort()
        val scope = CoroutineScope(currentCoroutineContext())
        val oauthClient = scope.DefaultMcpOAuthClient()
        val browser = scope.McpStreamableHttpClient()
        val configuration = McpServerConfiguration.StreamableHttp(
            url = "${fixture.origin}/mcp",
            oauth = McpOAuthConfiguration.Uninitialized(
                client = McpOAuthClient(
                    clientId = ClientId,
                    clientSecret = McpSecret(ClientSecret),
                    redirectUri = "http://127.0.0.1:$callbackPort/callback",
                    authorizationEndpoint = "${fixture.origin}/authorize",
                    tokenEndpoint = "${fixture.origin}/token",
                ),
                resource = "${fixture.origin}/mcp",
                scopes = listOf("tools.read", "tools.call"),
            ),
        )

        try {
            val attempt = oauthClient.create(configuration)
            val initialized = async { attempt.awaitInitialized() }

            val authorizationUrl = Url(attempt.authorizationUrl)
            val redirectUri = checkNotNull(authorizationUrl.parameters["redirect_uri"])
            val rejected = browser.get(
                "$redirectUri?code=wrong-code&state=wrong-state",
            )
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertFalse(initialized.isCompleted)

            val browserResponse = browser.get(attempt.authorizationUrl)
            assertEquals(HttpStatusCode.OK, browserResponse.status)
            val tokens = withTimeout(10.seconds) { initialized.await() }

            assertEquals(McpSecret("access-one"), tokens.accessToken)
            assertEquals(McpSecret("refresh-one"), tokens.refreshToken)
            assertEquals("${fixture.origin}/authorize", tokens.resolvedAuthorizationEndpoint)
            assertEquals("${fixture.origin}/token", tokens.resolvedTokenEndpoint)
            assertEquals(listOf("tools.read", "tools.call"), tokens.scopes)
            assertTrue(tokens.expiresAtEpochSeconds != null)

            val refreshed = oauthClient.refresh(tokens)

            assertEquals(McpSecret("access-two"), refreshed.accessToken)
            assertEquals(McpSecret("refresh-two"), refreshed.refreshToken)
            assertEquals(2, fixture.tokenRequests.size)
            val exchange = fixture.tokenRequests[0]
            assertEquals("authorization_code", exchange["grant_type"])
            assertEquals(AuthorizationCode, exchange["code"])
            assertEquals(ClientId, exchange["client_id"])
            assertEquals(ClientSecret, exchange["client_secret"])
            assertNotNull(exchange["code_verifier"])
            assertEquals("${fixture.origin}/mcp", exchange["resource"])
            val refresh = fixture.tokenRequests[1]
            assertEquals("refresh_token", refresh["grant_type"])
            assertEquals("refresh-one", refresh["refresh_token"])
            assertEquals("${fixture.origin}/mcp", refresh["resource"])
            assertEquals("tools.read tools.call", refresh["scope"])
        } finally {
            browser.close()
            fixture.stop()
        }
    }

    test("discovers a challenged resource and dynamically registers the OAuth client") {
        val fixture = DiscoverableMcpOAuthFixture().start()
        val callbackPort = reserveLoopbackPort()
        val scope = CoroutineScope(currentCoroutineContext())
        val oauthClient = scope.DefaultMcpOAuthClient()
        val browser = scope.McpStreamableHttpClient()
        val redirectUri = "http://127.0.0.1:$callbackPort/callback"
        val configuration = McpServerConfiguration.StreamableHttp(
            url = "${fixture.origin}/mcp",
            oauth = McpOAuthConfiguration.Uninitialized(
                client = McpOAuthClient(redirectUri = redirectUri),
            ),
        )

        try {
            val attempt = oauthClient.create(configuration)
            val prepared = attempt.preparedConfiguration

            assertEquals(DynamicClientId, prepared.client.clientId)
            assertEquals("${fixture.origin}/mcp", prepared.resource)
            assertEquals(listOf(DynamicScope), prepared.scopes)
            assertEquals(1, fixture.registrationRequests.size)
            val registration = fixture.registrationRequests.single()
            assertEquals("Kodex", registration.string("client_name"))
            assertEquals(
                listOf(redirectUri),
                registration.getValue("redirect_uris").jsonArray.map { value ->
                    value.jsonPrimitive.content
                },
            )
            assertEquals("none", registration.string("token_endpoint_auth_method"))
            assertEquals(
                listOf("authorization_code", "refresh_token"),
                registration.getValue("grant_types").jsonArray.map { value ->
                    value.jsonPrimitive.content
                },
            )

            val authorizationUrl = Url(attempt.authorizationUrl)
            assertEquals(DynamicClientId, authorizationUrl.parameters["client_id"])
            assertEquals("${fixture.origin}/mcp", authorizationUrl.parameters["resource"])
            assertEquals(DynamicScope, authorizationUrl.parameters["scope"])

            val initialized = async { attempt.awaitInitialized() }
            assertEquals(HttpStatusCode.OK, browser.get(attempt.authorizationUrl).status)
            val tokens = withTimeout(10.seconds) { initialized.await() }

            assertEquals(DynamicClientId, tokens.client.clientId)
            assertEquals(McpSecret("dynamic-access"), tokens.accessToken)
            assertEquals("${fixture.origin}/mcp", tokens.resource)
            assertEquals(listOf(DynamicScope), tokens.scopes)
            val tokenRequest = fixture.tokenRequests.single()
            assertEquals(DynamicClientId, tokenRequest["client_id"])
            assertEquals("${fixture.origin}/mcp", tokenRequest["resource"])
        } finally {
            browser.close()
            fixture.stop()
        }
    }

    test("rejects discovered authorization metadata without PKCE S256") {
        val fixture = DiscoverableMcpOAuthFixture(advertisePkce = false).start()
        val callbackPort = reserveLoopbackPort()
        val oauthClient = CoroutineScope(currentCoroutineContext()).DefaultMcpOAuthClient()
        val configuration = McpServerConfiguration.StreamableHttp(
            url = "${fixture.origin}/mcp",
            oauth = McpOAuthConfiguration.Uninitialized(
                client = McpOAuthClient(
                    redirectUri = "http://127.0.0.1:$callbackPort/callback",
                ),
            ),
        )

        try {
            val failure = assertFailsWith<IllegalArgumentException> {
                oauthClient.create(configuration)
            }

            assertTrue(failure.message.orEmpty().contains("PKCE S256"))
            assertTrue(fixture.registrationRequests.isEmpty())
        } finally {
            fixture.stop()
        }
    }
}

private class McpOAuthFixture {
    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, host = Host, port = 0) {
        routing {
            get("/authorize") {
                val redirectUri = checkNotNull(call.request.queryParameters["redirect_uri"])
                val state = checkNotNull(call.request.queryParameters["state"])
                check(call.request.queryParameters["client_id"] == ClientId)
                check(call.request.queryParameters["response_type"] == "code")
                check(call.request.queryParameters["code_challenge_method"] == "S256")
                check(!call.request.queryParameters["code_challenge"].isNullOrBlank())
                call.respondRedirect(
                    "$redirectUri?code=$AuthorizationCode&state=$state",
                )
            }
            post("/token") {
                val parameters = call.receiveParameters()
                tokenRequests += parameters.entries().associate { (name, values) ->
                    name to values.single()
                }
                val refresh = parameters["grant_type"] == "refresh_token"
                call.respondText(
                    text = if (refresh) {
                        """
                        {
                          "access_token": "access-two",
                          "refresh_token": "refresh-two",
                          "token_type": "Bearer",
                          "expires_in": 3600
                        }
                        """.trimIndent()
                    } else {
                        """
                        {
                          "access_token": "access-one",
                          "refresh_token": "refresh-one",
                          "token_type": "Bearer",
                          "expires_in": 60
                        }
                        """.trimIndent()
                    },
                    contentType = ContentType.Application.Json,
                )
            }
        }
    }
    val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    var origin: String = ""
        private set

    suspend fun start(): McpOAuthFixture {
        server.startSuspend(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        origin = "http://$Host:$port"
        return this
    }

    fun stop() {
        server.stop()
    }
}

private class DiscoverableMcpOAuthFixture(
    private val advertisePkce: Boolean = true,
) {
    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, host = Host, port = 0) {
        routing {
            post("/mcp") {
                call.response.header(
                    HttpHeaders.WWWAuthenticate,
                    """Basic realm="ignored", Bearer resource_metadata="$origin/resource-metadata", scope="$DynamicScope"""",
                )
                call.respond(HttpStatusCode.Unauthorized)
            }
            get("/resource-metadata") {
                call.respondText(
                    text = """
                        {
                          "resource": "$origin/mcp",
                          "authorization_servers": ["$origin"],
                          "scopes_supported": ["metadata:scope"]
                        }
                    """.trimIndent(),
                    contentType = ContentType.Application.Json,
                )
            }
            get("/.well-known/oauth-authorization-server") {
                call.respondText(
                    text = """
                        {
                          "issuer": "$origin",
                          "authorization_endpoint": "$origin/authorize",
                          "token_endpoint": "$origin/token",
                          "registration_endpoint": "$origin/register",
                          "token_endpoint_auth_methods_supported": ["none"]
                          ${if (advertisePkce) """, "code_challenge_methods_supported": ["S256"]""" else ""}
                        }
                    """.trimIndent(),
                    contentType = ContentType.Application.Json,
                )
            }
            post("/register") {
                registrationRequests += OAuthTestJson
                    .parseToJsonElement(call.receiveText())
                    .jsonObject
                call.respondText(
                    text = """
                        {
                          "client_id": "$DynamicClientId",
                          "token_endpoint_auth_method": "none"
                        }
                    """.trimIndent(),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Created,
                )
            }
            get("/authorize") {
                val redirectUri = checkNotNull(call.request.queryParameters["redirect_uri"])
                val state = checkNotNull(call.request.queryParameters["state"])
                check(call.request.queryParameters["client_id"] == DynamicClientId)
                check(call.request.queryParameters["scope"] == DynamicScope)
                check(call.request.queryParameters["resource"] == "$origin/mcp")
                call.respondRedirect(
                    "$redirectUri?code=$AuthorizationCode&state=$state",
                )
            }
            post("/token") {
                val parameters = call.receiveParameters()
                tokenRequests += parameters.entries().associate { (name, values) ->
                    name to values.single()
                }
                call.respondText(
                    text = """
                        {
                          "access_token": "dynamic-access",
                          "refresh_token": "dynamic-refresh",
                          "token_type": "Bearer",
                          "expires_in": 3600
                        }
                    """.trimIndent(),
                    contentType = ContentType.Application.Json,
                )
            }
        }
    }
    val registrationRequests = CopyOnWriteArrayList<JsonObject>()
    val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    var origin: String = ""
        private set

    suspend fun start(): DiscoverableMcpOAuthFixture {
        server.startSuspend(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        origin = "http://$Host:$port"
        return this
    }

    fun stop() {
        server.stop()
    }
}

private fun reserveLoopbackPort(): Int =
    ServerSocket(0, 1).use { socket -> socket.localPort }

private const val Host: String = "127.0.0.1"
private const val ClientId: String = "test-client"
private const val ClientSecret: String = "test-secret"
private const val AuthorizationCode: String = "authorization-code"
private const val DynamicClientId: String = "dynamic-client"
private const val DynamicScope: String = "device:full"

private val OAuthTestJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.content
