package io.github.stream29.codex.lite.openai.client

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.codex.lite.openai.OpenAiLoginAuthorization
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlin.test.assertEquals

val openAiLoginClientTest by testSuite {
    test("authorization URL uses the Codex PKCE protocol fields") {
        val client = OpenAiLoginClient(
            MockEngine { error("Authorization URL does not make a request.") },
        )
        try {
            val url = Url(
                client.authorizationUrl(
                    OpenAiLoginAuthorization(
                        redirectUri = "http://127.0.0.1:1455/auth/callback",
                        codeChallenge = "challenge",
                        state = "state",
                        allowedWorkspaceIds = listOf("workspace-a", "workspace-b"),
                    ),
                ),
            )

            assertEquals("code", url.parameters["response_type"])
            assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", url.parameters["client_id"])
            assertEquals("http://127.0.0.1:1455/auth/callback", url.parameters["redirect_uri"])
            assertEquals("openid profile email offline_access api.connectors.read api.connectors.invoke", url.parameters["scope"])
            assertEquals("challenge", url.parameters["code_challenge"])
            assertEquals("S256", url.parameters["code_challenge_method"])
            assertEquals("true", url.parameters["id_token_add_organizations"])
            assertEquals("true", url.parameters["codex_cli_simplified_flow"])
            assertEquals("state", url.parameters["state"])
            assertEquals("codex_cli_rs", url.parameters["originator"])
            assertEquals("workspace-a,workspace-b", url.parameters["allowed_workspace_id"])
        } finally {
            client.close()
        }
    }

    test("authorization-code exchange posts a form-encoded PKCE request") {
        val client = OpenAiLoginClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("https://auth.openai.com/oauth/token", request.url.toString())
                assertEquals(
                    ContentType.Application.FormUrlEncoded,
                    requireNotNull(request.body.contentType).withoutParameters(),
                )
                val form = parseQueryString(
                    (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                )
                assertEquals("authorization_code", form["grant_type"])
                assertEquals("authorization-code", form["code"])
                assertEquals("http://127.0.0.1:1455/auth/callback", form["redirect_uri"])
                assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", form["client_id"])
                assertEquals("verifier", form["code_verifier"])
                respond(
                    content = """{"id_token":"id","access_token":"access","refresh_token":"refresh"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        try {
            val tokens = client.exchangeAuthorizationCode(
                OpenAiAuthorizationCodeExchange(
                    authorizationCode = "authorization-code",
                    redirectUri = "http://127.0.0.1:1455/auth/callback",
                    codeVerifier = "verifier",
                ),
            )

            assertEquals("id", tokens.idToken)
            assertEquals("access", tokens.accessToken)
            assertEquals("refresh", tokens.refreshToken)
        } finally {
            client.close()
        }
    }

    test("refresh posts the Codex-compatible JSON payload") {
        val client = OpenAiLoginClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("https://auth.openai.com/oauth/token", request.url.toString())
                assertEquals(ContentType.Application.Json, request.body.contentType)
                assertEquals(
                    """{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann","grant_type":"refresh_token","refresh_token":"old-refresh"}""",
                    (request.body as TextContent).text,
                )
                respond(
                    content = """{"access_token":"new-access","refresh_token":"new-refresh"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        try {
            val response = client.refreshSubscriptionTokens("old-refresh")

            assertEquals(null, response.idToken)
            assertEquals("new-access", response.accessToken)
            assertEquals("new-refresh", response.refreshToken)
        } finally {
            client.close()
        }
    }
}
