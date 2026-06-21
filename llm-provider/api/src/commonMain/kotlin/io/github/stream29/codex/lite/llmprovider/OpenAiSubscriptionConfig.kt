package io.github.stream29.codex.lite.llmprovider

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

public data class OpenAiSubscriptionLlmProviderConfig(
    public val baseUrl: String = "https://chatgpt.com/backend-api/codex",
    public val clientVersion: String = "0.1.0",
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val maxRetries: Int = 2,
    public val retryBaseDelayMs: Long = 250,
)

public fun OpenAiSubscriptionLlmProviderHttpClient(
    json: Json = OpenAiSubscriptionLlmProvider.defaultJson,
): HttpClient =
    HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(CodexSseCompatibility)
    }

internal fun OpenAiSubscriptionLlmProviderConfig.url(path: String): String =
    "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
