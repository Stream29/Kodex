package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSearchClientTest {
    @Test
    fun searchPostsToAlphaSearchWithAuthHeaders() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = """{"encrypted_output":"ciphertext","output":"search result"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(WebSearchClient.defaultJson)
            }
        }
        val client = WebSearchClient(
            authProvider = {
                OpenAiSubscriptionAuth(
                    accessToken = "token",
                    accountId = "account",
                    isFedrampAccount = true,
                )
            },
            httpClient = httpClient,
            config = WebSearchClientConfig(
                baseUrl = "https://chatgpt.example/backend-api/codex/",
                clientVersion = "0.141.0",
            ),
        )

        val response = client.search(
            SearchRequest(
                id = "session",
                model = "model",
                commands = SearchCommands(searchQuery = listOf(SearchQuery("weather"))),
            ),
        )

        assertEquals(SearchResponse(encryptedOutput = "ciphertext", output = "search result"), response)
        val request = captured ?: error("request should be captured")
        assertEquals("https://chatgpt.example/backend-api/codex/alpha/search", request.url.toString())
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        assertEquals("account", request.headers["ChatGPT-Account-ID"])
        assertEquals("true", request.headers["X-OpenAI-Fedramp"])
        assertEquals("0.141.0", request.headers["version"])
    }
}
