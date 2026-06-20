package io.github.stream29.codex.lite.llmprovider

import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuth
import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.time.Duration.Companion.milliseconds

public class OpenAiSubscriptionLlmProvider(
    private val authProvider: OpenAiSubscriptionAuthProvider,
    private val httpClient: HttpClient = OpenAiSubscriptionLlmProviderHttpClient(),
    private val config: OpenAiSubscriptionLlmProviderConfig = OpenAiSubscriptionLlmProviderConfig(),
    private val json: Json = defaultJson,
) : LlmProvider {
    override suspend fun listModels(): LlmModels {
        val response = executeWithRetry {
            httpClient.getWithAuth("models", ContentType.Application.Json) {
                parameter("client_version", config.clientVersion)
            }.also { it.throwIfUnsuccessful() }
        }
        return response.body()
    }

    override suspend fun createResponse(request: LlmResponseRequest): LlmResponse {
        val events = streamResponse(request).toList()
        val completed = events.asReversed().firstNotNullOfOrNull { event ->
            event as? LlmResponseStreamEvent.Completed
        } ?: throw LlmProviderException(message = "Response stream did not contain a completed response.")

        return completed.response.copy(
            output = events.filterIsInstance<LlmResponseStreamEvent.OutputItemDone>().map { it.item },
        )
    }

    override fun streamResponse(request: LlmResponseRequest): Flow<LlmResponseStreamEvent> = flow {
        var emittedAny = false
        var attempt = 0
        while (true) {
            try {
                val response = postResponse(request, stream = true, accept = ContentType.Text.EventStream)
                response.throwIfUnsuccessful()
                response.body<SSESession>()
                    .incoming
                    .toLlmResponseStreamEvents(json)
                    .collect { event ->
                        emittedAny = true
                        emit(event)
                    }
                return@flow
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (emittedAny || !shouldRetry(error, attempt)) throw error
                beforeRetry(error)
                delay(retryDelay(attempt).milliseconds)
                attempt += 1
            }
        }
    }

    override suspend fun compactResponse(request: LlmCompactionRequest): LlmCompactionResponse {
        val response = executeWithRetry {
            httpClient.postWithAuth("responses/compact", ContentType.Application.Json) {
                contentType(ContentType.Application.Json)
                setBody(request.toResponsesApiRequest(json))
            }.also { it.throwIfUnsuccessful() }
        }
        return json.decodeFromString(LlmCompactionResponse.serializer(), response.bodyAsText())
    }

    private suspend fun postResponse(
        request: LlmResponseRequest,
        stream: Boolean,
        accept: ContentType,
    ): HttpResponse = httpClient.postWithAuth("responses", accept) {
        contentType(ContentType.Application.Json)
        if (stream) {
            acceptCodexSseWithoutContentType()
        }
        setBody(request.copy(stream = stream).toResponsesApiRequest(json))
    }

    private suspend fun HttpClient.getWithAuth(
        path: String,
        accept: ContentType,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val auth = authProvider.currentAuth()
        return get {
            url(config.url(path))
            accept(accept)
            applyDefaultHeaders(auth)
            block()
        }
    }

    private suspend fun HttpClient.postWithAuth(
        path: String,
        accept: ContentType,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val auth = authProvider.currentAuth()
        return post {
            url(config.url(path))
            accept(accept)
            applyDefaultHeaders(auth)
            block()
        }
    }

    private fun HttpRequestBuilder.applyDefaultHeaders(auth: OpenAiSubscriptionAuth) {
        config.defaultHeaders.forEach { (name, value) -> header(name, value) }
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
        val parsed = runCatching { json.decodeFromString(OpenAiSubscriptionErrorBody.serializer(), text).errorLike() }
            .getOrNull()
        throw LlmProviderException(
            status = status.value,
            message = parsed?.message ?: "OpenAI subscription request failed with HTTP ${status.value}.",
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
        if (error is LlmProviderException) {
            return error.status == 401 || error.status in retryableStatuses
        }
        return true
    }

    private suspend fun beforeRetry(error: Throwable) {
        if (error is LlmProviderException && error.status == 401) {
            authProvider.refreshAuth()
        }
    }

    private fun retryDelay(attempt: Int): Long =
        config.retryBaseDelayMs * (1L shl attempt.coerceAtMost(10))

    public companion object {
        @OptIn(ExperimentalSerializationApi::class)
        public val defaultJson: Json = Json {
            serializersModule = SerializersModule {
                polymorphic(LlmResponseItem::class) {
                    defaultDeserializer { LlmResponseItem.Other.serializer() }
                }
                polymorphic(LlmWebSearchAction::class) {
                    defaultDeserializer { LlmWebSearchAction.Other.serializer() }
                }
            }
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        private val retryableStatuses: Set<Int> = setOf(408, 409, 429, 500, 502, 503, 504)
    }
}
