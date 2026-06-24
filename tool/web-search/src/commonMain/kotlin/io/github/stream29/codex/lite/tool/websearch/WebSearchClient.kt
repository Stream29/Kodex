package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuth
import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuthProvider
import io.github.stream29.codex.lite.llmprovider.LlmProviderJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

public data class WebSearchClientConfig(
    public val baseUrl: String = "https://chatgpt.com/backend-api/codex",
    public val clientVersion: String = "0.1.0",
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val maxRetries: Int = 2,
    public val retryBaseDelayMs: Long = 250,
)

public class WebSearchClient(
    private val authProvider: OpenAiSubscriptionAuthProvider,
    private val httpClient: HttpClient = WebSearchHttpClient(),
    private val config: WebSearchClientConfig = WebSearchClientConfig(),
    private val json: Json = defaultJson,
) {
    public suspend fun search(request: SearchRequest): SearchResponse {
        val response = executeWithRetry {
            val auth = authProvider.currentAuth()
            httpClient.post {
                url(config.url("alpha/search"))
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                applyDefaultHeaders(auth)
                setBody(request)
            }.also { it.throwIfUnsuccessful() }
        }
        return response.body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyDefaultHeaders(auth: OpenAiSubscriptionAuth) {
        config.defaultHeaders.forEach { (name, value) -> header(name, value) }
        if (!config.defaultHeaders.keys.any { it.equals("version", ignoreCase = true) }) {
            header("version", config.clientVersion)
        }
        header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
        auth.accountId?.let { header("ChatGPT-Account-ID", it) }
        if (auth.isFedrampAccount) {
            header("X-OpenAI-Fedramp", "true")
        }
    }

    private suspend fun HttpResponse.throwIfUnsuccessful() {
        if (status.isSuccess()) {
            return
        }
        val text = bodyAsText()
        val parsed = runCatching { json.decodeFromString(WebSearchErrorBody.serializer(), text).errorLike() }
            .getOrNull()
        throw WebSearchException(
            status = status.value,
            message = parsed?.message ?: "Web search request failed with HTTP ${status.value}.",
            responseBody = text,
            code = parsed?.code,
            type = parsed?.type,
            requestId = headers["x-request-id"],
            cfRay = headers["cf-ray"],
        )
    }

    private suspend fun <T> executeWithRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!shouldRetry(error, attempt)) throw error
                beforeRetry(error)
                delay(retryDelay(attempt).milliseconds)
                attempt += 1
            }
        }
    }

    private fun shouldRetry(error: Throwable, attempt: Int): Boolean {
        if (attempt >= config.maxRetries) {
            return false
        }
        if (error is WebSearchException) {
            return error.status == 401 || error.status in retryableStatuses
        }
        return true
    }

    private suspend fun beforeRetry(error: Throwable) {
        if (error is WebSearchException && error.status == 401) {
            authProvider.refreshAuth()
        }
    }

    private fun retryDelay(attempt: Int): Long =
        config.retryBaseDelayMs * (1L shl attempt.coerceAtMost(10))

    public companion object {
        public val defaultJson: Json = LlmProviderJson.default

        private val retryableStatuses: Set<Int> = setOf(408, 409, 429, 500, 502, 503, 504)
    }
}

public fun WebSearchHttpClient(
    json: Json = WebSearchClient.defaultJson,
): HttpClient =
    HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
    }

internal fun WebSearchClientConfig.url(path: String): String =
    "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

/**
 * @property status Nullable because failures may occur before an HTTP response;
 * `null` means no HTTP status was available.
 * @property responseBody Nullable because failures may not include a response
 * body; `null` means no body was available.
 * @property code Nullable because backend errors may omit a code; `null` means
 * no structured error code was provided.
 * @property type Nullable because backend errors may omit a type; `null` means
 * no structured error type was provided.
 * @property requestId Nullable because responses may omit request id headers;
 * `null` means no request id was available.
 * @property cfRay Nullable because responses may omit Cloudflare ray headers;
 * `null` means no ray id was available.
 */
public class WebSearchException(
    public val status: Int? = null,
    override val message: String,
    public val responseBody: String? = null,
    public val code: String? = null,
    public val type: String? = null,
    public val requestId: String? = null,
    public val cfRay: String? = null,
) : Exception(message)

/**
 * @property error Nullable because the backend may return a nested error
 * object or flat fields; `null` means no nested object was present.
 * @property detail Nullable because flat error payloads may omit detail; `null`
 * means no detail string was present.
 * @property message Nullable because flat error payloads may omit message;
 * `null` means no message string was present.
 * @property code Nullable because flat error payloads may omit code; `null`
 * means no code was present.
 * @property type Nullable because flat error payloads may omit type; `null`
 * means no type was present.
 */
@Serializable
private data class WebSearchErrorBody(
    val error: WebSearchError? = null,
    val detail: String? = null,
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
) {
    fun errorLike(): WebSearchError =
        error ?: WebSearchError(message = message ?: detail, code = code, type = type)
}

/**
 * @property message Nullable because backend errors may omit message; `null`
 * means no message string was provided.
 * @property code Nullable because backend errors may omit code; `null` means no
 * code was provided.
 * @property type Nullable because backend errors may omit type; `null` means no
 * type was provided.
 */
@Serializable
private data class WebSearchError(
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
)
