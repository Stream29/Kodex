package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuth
import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuthProvider
import io.github.stream29.codex.lite.llmprovider.LlmProviderJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
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

public data class OpenAiImageGenerationClientConfig(
    public val baseUrl: String = "https://chatgpt.com/backend-api/codex",
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val maxRetries: Int = 2,
    public val retryBaseDelayMs: Long = 250,
)

public class OpenAiImageGenerationClient(
    private val authProvider: OpenAiSubscriptionAuthProvider,
    private val httpClient: HttpClient = OpenAiImageGenerationHttpClient(),
    private val config: OpenAiImageGenerationClientConfig = OpenAiImageGenerationClientConfig(),
    private val json: Json = defaultJson,
) {
    public suspend fun generate(request: ImageGenerationRequest): ImageResponse =
        postImageRequest("images/generations", request, operation = "image generation")

    public suspend fun edit(request: ImageEditRequest): ImageResponse =
        postImageRequest("images/edits", request, operation = "image edit")

    private suspend inline fun <reified T : Any> postImageRequest(
        path: String,
        request: T,
        operation: String,
    ): ImageResponse {
        val response = executeWithRetry {
            val auth = authProvider.currentAuth()
            httpClient.post {
                url(config.url(path))
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                applyDefaultHeaders(auth)
                setBody(request)
            }.also { it.throwIfUnsuccessful(operation) }
        }
        return response.body()
    }

    private fun HttpRequestBuilder.applyDefaultHeaders(auth: OpenAiSubscriptionAuth) {
        config.defaultHeaders.forEach { (name, value) -> header(name, value) }
        header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
        auth.accountId?.let { header("ChatGPT-Account-ID", it) }
        if (auth.isFedrampAccount) {
            header("X-OpenAI-Fedramp", "true")
        }
    }

    private suspend fun HttpResponse.throwIfUnsuccessful(operation: String) {
        if (status.isSuccess()) {
            return
        }
        val text = bodyAsText()
        val parsed = runCatching { json.decodeFromString(OpenAiImageGenerationErrorBody.serializer(), text).errorLike() }
            .getOrNull()
        throw OpenAiImageGenerationException(
            status = status.value,
            message = parsed?.message ?: "OpenAI $operation request failed with HTTP ${status.value}.",
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
        if (error is OpenAiImageGenerationException) {
            return error.status == 401 || error.status in retryableStatuses
        }
        return true
    }

    private suspend fun beforeRetry(error: Throwable) {
        if (error is OpenAiImageGenerationException && error.status == 401) {
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

public fun OpenAiImageGenerationHttpClient(
    json: Json = OpenAiImageGenerationClient.defaultJson,
    requestTimeoutMillis: Long = 300_000,
): HttpClient =
    HttpClient {
        val imageRequestTimeoutMillis = requestTimeoutMillis
        install(HttpTimeout) {
            this.requestTimeoutMillis = imageRequestTimeoutMillis
            this.socketTimeoutMillis = imageRequestTimeoutMillis
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

internal fun OpenAiImageGenerationClientConfig.url(path: String): String =
    "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

/**
 * @property status Nullable because failures may occur before an HTTP response;
 * `null` means no HTTP status was available.
 * @property responseBody Nullable because failures before response parsing may
 * not have a body; `null` means no response body was available.
 * @property code Nullable because OpenAI error payloads may omit it.
 * @property type Nullable because OpenAI error payloads may omit it.
 * @property requestId Nullable because the server may omit `x-request-id`.
 * @property cfRay Nullable because Cloudflare may omit `cf-ray`.
 */
public class OpenAiImageGenerationException(
    public val status: Int? = null,
    override val message: String,
    public val responseBody: String? = null,
    public val code: String? = null,
    public val type: String? = null,
    public val requestId: String? = null,
    public val cfRay: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
internal data class OpenAiImageGenerationErrorBody(
    val error: OpenAiImageGenerationError? = null,
    val message: String? = null,
    val detail: String? = null,
    val code: String? = null,
    val type: String? = null,
) {
    fun errorLike(): OpenAiImageGenerationError =
        error ?: OpenAiImageGenerationError(message = message ?: detail, code = code, type = type)
}

@Serializable
internal data class OpenAiImageGenerationError(
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
)
