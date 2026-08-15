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
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            assertEquals("tools.read tools.call", refresh["scope"])
        } finally {
            browser.close()
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

private fun reserveLoopbackPort(): Int =
    ServerSocket(0, 1).use { socket -> socket.localPort }

private const val Host: String = "127.0.0.1"
private const val ClientId: String = "test-client"
private const val ClientSecret: String = "test-secret"
private const val AuthorizationCode: String = "authorization-code"
