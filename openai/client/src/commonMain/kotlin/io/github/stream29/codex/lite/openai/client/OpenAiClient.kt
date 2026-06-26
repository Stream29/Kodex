package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.CompactionResponse
import io.github.stream29.codex.lite.openai.ImageEditRequest
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiJson
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuth
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthProvider
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.SearchRequest
import io.github.stream29.codex.lite.openai.SearchResponse
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val CodexDefaultOriginator: String = "codex_cli_rs"
private val OpenAiClientJson = OpenAiJson.default

public class OpenAiClient(
    private val authProvider: OpenAiSubscriptionAuthProvider,
    private val config: OpenAiClientConfig = OpenAiClientConfig(),
) : OpenAiClientContract {
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout)
        install(ContentNegotiation) {
            json(OpenAiClientJson)
        }
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
        install(CodexSseCompatibility)
    }

    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> {
        val auth = authProvider.currentAuth()
        val response = httpClient.get {
            url(config.url("models"))
            accept(ContentType.Application.Json)
            parameter("client_version", config.clientVersion)
            applyDefaultHeaders(auth)
        }
        return response.openAiResultBody()
    }

    override fun createResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent> = flow {
        val auth = authProvider.currentAuth()
        httpClient.post {
            url(config.url("responses"))
            accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            acceptCodexSseWithoutContentType()
            applyDefaultHeaders(auth)
            setBody(request)
        }.body<SSESession>()
            .incoming
            .toResponsesStreamEvents(OpenAiClientJson)
            .collect { emit(it) }
    }

    override suspend fun compactResponse(request: CompactionInput): OpenAiResponseResult<CompactionResponse> {
        val auth = authProvider.currentAuth()
        val response = httpClient.post {
            url(config.url("responses/compact"))
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = config.requestTimeoutMillis
                socketTimeoutMillis = config.requestTimeoutMillis
            }
            applyDefaultHeaders(auth)
            setBody(request)
        }
        return response.openAiResultBody()
    }

    override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse> {
        val auth = authProvider.currentAuth()
        val response = httpClient.post {
            url(config.url("images/generations"))
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = config.requestTimeoutMillis
                socketTimeoutMillis = config.requestTimeoutMillis
            }
            applyOpenAiHeaders(auth)
            setBody(request)
        }
        return response.openAiResultBody()
    }

    override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse> {
        val auth = authProvider.currentAuth()
        val response = httpClient.post {
            url(config.url("images/edits"))
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = config.requestTimeoutMillis
                socketTimeoutMillis = config.requestTimeoutMillis
            }
            applyOpenAiHeaders(auth)
            setBody(request)
        }
        return response.openAiResultBody()
    }

    override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse> {
        val auth = authProvider.currentAuth()
        val response = httpClient.post {
            url(config.url("alpha/search"))
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            timeout {
                requestTimeoutMillis = config.requestTimeoutMillis
                socketTimeoutMillis = config.requestTimeoutMillis
            }
            applyOpenAiHeaders(auth, includeVersionHeader = true)
            setBody(request)
        }
        return response.openAiResultBody()
    }

    override fun close(): Unit {
        httpClient.close()
    }

    private fun HttpRequestBuilder.applyDefaultHeaders(auth: OpenAiSubscriptionAuth) {
        applyOpenAiHeaders(auth)
    }

    private suspend inline fun <reified T> HttpResponse.openAiResultBody(): OpenAiResponseResult<T> =
        body()

    private fun HttpRequestBuilder.applyOpenAiHeaders(
        auth: OpenAiSubscriptionAuth,
        includeVersionHeader: Boolean = false,
    ) {
        setHeader("originator", CodexDefaultOriginator)
        setHeader(HttpHeaders.UserAgent, codexUserAgent(config.clientVersion))
        if (includeVersionHeader) {
            setHeader("version", config.clientVersion)
        }
        config.defaultHeaders.forEach { (name, value) -> setHeader(name, value) }
        setHeader(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
        auth.accountId?.let { accountId -> setHeader("ChatGPT-Account-ID", accountId) }
        if (auth.isFedrampAccount) {
            setHeader("X-OpenAI-Fedramp", "true")
        }
    }

    private fun HttpRequestBuilder.setHeader(name: String, value: String) {
        headers.remove(name)
        header(name, value)
    }
}

private fun codexUserAgent(clientVersion: String): String =
    sanitizeHeaderValue("$CodexDefaultOriginator/$clientVersion (CodexLite)")

private fun sanitizeHeaderValue(candidate: String): String {
    val sanitized = candidate.map { char ->
        if (char in ' '..'~') char else '_'
    }.joinToString(separator = "")
    return sanitized.ifBlank { CodexDefaultOriginator }
}
