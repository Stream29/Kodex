package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.CompactionResponse
import io.github.stream29.codex.lite.openai.ImageEditRequest
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.SearchRequest
import io.github.stream29.codex.lite.openai.SearchResponse
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.ktorclientext.ChatGptAccountId
import io.github.stream29.codex.lite.utils.ktorclientext.CodexOriginator
import io.github.stream29.codex.lite.utils.ktorclientext.OpenAiSearchVersion
import io.github.stream29.codex.lite.utils.ktorclientext.SseCompatibility
import io.github.stream29.codex.lite.utils.ktorclientext.addAll
import io.github.stream29.codex.lite.utils.ktorclientext.set
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

public class OpenAiClient(
    private val authProvider: OpenAiSubscriptionAuthSession,
    private val config: OpenAiClientConfig = OpenAiClientConfig(),
) : OpenAiClientContract {
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            socketTimeoutMillis = config.requestTimeoutMillis
        }
        install(ContentNegotiation) {
            json(OpenAiJsonCodec)
        }
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
        install(SseCompatibility)
        defaultRequest {
            url(config.baseUrl.trimEnd('/') + "/")
            headers[HttpHeaders.CodexOriginator] = config.originator
            headers[HttpHeaders.UserAgent] = config.userAgent
            headers.addAll(config.defaultHeaders)
            val (accessToken, accountId) = authProvider.stateFlow.value
            bearerAuth(accessToken)
            headers[HttpHeaders.ChatGptAccountId] = accountId
        }
    }

    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> {
        val response = httpClient.get {
            url {
                appendPathSegments("models")
            }
            accept(ContentType.Application.Json)
            parameter("client_version", config.clientVersion)
        }
        return response.body()
    }

    override suspend fun createResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent> {
        val response = httpClient.post {
            url {
                appendPathSegments("responses")
            }
            accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.body<SSESession>()
            .incoming
            .mapNotNull { event -> event.data.takeIf { it != "[DONE]" } }
            .map { OpenAiJsonCodec.decodeFromString<ResponsesStreamEvent>(it) }
    }

    override suspend fun compactResponse(request: CompactionInput): OpenAiResponseResult<CompactionResponse> {
        val response = httpClient.post {
            url {
                appendPathSegments("responses", "compact")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.body()
    }

    override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse> {
        val response = httpClient.post {
            url {
                appendPathSegments("images", "generations")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.body()
    }

    override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse> {
        val response = httpClient.post {
            url {
                appendPathSegments("images", "edits")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.body()
    }

    override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse> {
        val response = httpClient.post {
            url {
                appendPathSegments("alpha", "search")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            headers[HttpHeaders.OpenAiSearchVersion] = config.clientVersion
            setBody(request)
        }
        return response.body()
    }

    override fun close(): Unit {
        httpClient.close()
    }

}
