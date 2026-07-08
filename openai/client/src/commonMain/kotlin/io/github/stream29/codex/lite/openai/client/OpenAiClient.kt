package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.CompactionResponse
import io.github.stream29.codex.lite.openai.ImageEditRequest
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseError
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ResponseItem
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
import io.ktor.client.plugins.HttpRequestRetry
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
import io.ktor.client.statement.bodyAsText
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.io.IOException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

public class OpenAiClient(
    private val authProvider: OpenAiSubscriptionAuthSession,
    private val config: OpenAiClientConfig = OpenAiClientConfig(),
) : OpenAiClientContract {
    private val httpClient: HttpClient = HttpClient {
        install(HttpRequestRetry) {
            maxRetries = config.retry.maxRetries
            retryIf { _, response ->
                response.status.isRetryableOpenAiStatus(config.retry)
            }
            retryOnExceptionIf { _, cause ->
                cause.isRetryableOpenAiTransportException(config.retry)
            }
            exponentialDelay(
                baseDelayMs = config.retry.baseDelayMillis,
                maxDelayMs = config.retry.maxDelayMillis,
                randomizationMs = config.retry.randomizationMillis,
            )
        }
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

    override suspend fun createResponse(
        request: ResponsesApiRequest,
        extraHeaders: Map<String, String>,
    ): Flow<ResponsesStreamEvent> {
        val response = httpClient.post {
            url {
                appendPathSegments("responses")
            }
            accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            headers.addAll(extraHeaders)
            setBody(request)
        }
        return response.body<SSESession>()
            .incoming
            .mapNotNull { event -> event.data.takeIf { it != "[DONE]" } }
            .map { OpenAiJsonCodec.decodeFromString<ResponsesStreamEvent>(it) }
    }

    override suspend fun createRemoteCompactionV2Response(
        request: RemoteCompactionV2Request,
    ): RemoteCompactionV2Response =
        retryOpenAiStreamingTransport(config.retry) {
            createResponse(
                request = request.toResponsesApiRequest(),
                extraHeaders = request.remoteCompactionV2ExtraHeaders(),
            ).collectRemoteCompactionV2Response()
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
        return OpenAiJsonCodec.decodeFromString(response.bodyAsText())
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

internal fun RemoteCompactionV2Request.toResponsesApiRequest(): ResponsesApiRequest {
    val metadata = remoteCompactionV2TurnMetadata()
    val requestSettings = settings
    return ResponsesApiRequest(
        model = requestSettings.model,
        input = history + ResponseItem.CompactionTrigger,
        instructions = requestSettings.instructions,
        store = requestSettings.store,
        previousResponseId = requestSettings.previousResponseId,
        tools = requestSettings.tools,
        toolChoice = requestSettings.toolChoice,
        parallelToolCalls = requestSettings.parallelToolCalls,
        reasoning = requestSettings.reasoning,
        include = requestSettings.include,
        serviceTier = requestSettings.serviceTier,
        promptCacheKey = requestSettings.promptCacheKey,
        text = requestSettings.text,
        clientMetadata = requestSettings.clientMetadata + remoteCompactionV2ClientMetadata(metadata),
    )
}

internal fun RemoteCompactionV2Request.remoteCompactionV2ExtraHeaders(): Map<String, String> =
    buildMap {
        put(HeaderCodexBetaFeatures, RemoteCompactionV2Feature)
        settings.installationId?.let { put(HeaderCodexInstallationId, it) }
        put(HeaderCodexTurnMetadata, remoteCompactionV2TurnMetadata())
        put(HeaderCodexWindowId, checkpoint.windowId.toString())
    }

internal fun RemoteCompactionV2Request.remoteCompactionV2TurnMetadata(): String =
    OpenAiJsonCodec.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            settings.installationId?.let { put("installation_id", it) }
            settings.sessionId?.let { put("session_id", it) }
            settings.threadId?.let { put("thread_id", it) }
            put("turn_id", turnId)
            put("window_id", checkpoint.windowId.toString())
            put("request_kind", "compaction")
            put(
                "compaction",
                buildJsonObject {
                    put("trigger", trigger.wireName)
                    put("reason", reason.wireName)
                    put("implementation", "responses_compaction_v2")
                    put("phase", phase.wireName)
                    put("strategy", "memento")
                },
            )
        },
    )

private fun RemoteCompactionV2Request.remoteCompactionV2ClientMetadata(
    turnMetadata: String,
): Map<String, String> =
    buildMap {
        settings.installationId?.let { put(HeaderCodexInstallationId, it) }
        settings.sessionId?.let { put("session_id", it) }
        settings.threadId?.let { put("thread_id", it) }
        put("turn_id", turnId)
        put(HeaderCodexWindowId, checkpoint.windowId.toString())
        put(HeaderCodexTurnMetadata, turnMetadata)
    }

private const val RemoteCompactionV2Feature: String = "remote_compaction_v2"
private const val HeaderCodexBetaFeatures: String = "x-codex-beta-features"
private const val HeaderCodexInstallationId: String = "x-codex-installation-id"
private const val HeaderCodexTurnMetadata: String = "x-codex-turn-metadata"
private const val HeaderCodexWindowId: String = "x-codex-window-id"

internal suspend fun Flow<ResponsesStreamEvent>.collectRemoteCompactionV2Response(): RemoteCompactionV2Response {
    var outputItemCount = 0
    var compactionCount = 0
    var compactionOutput: ResponseItem.Compaction? = null
    var completedCount = 0
    var completedResponse: Response? = null

    collect { event ->
        when (event) {
            is ResponsesStreamEvent.OutputItemDone -> {
                outputItemCount += 1
                val item = event.item
                if (item is ResponseItem.Compaction) {
                    compactionCount += 1
                    if (compactionOutput == null) {
                        compactionOutput = item
                    }
                }
            }

            is ResponsesStreamEvent.Completed -> {
                completedCount += 1
                completedResponse = event.response
            }

            is ResponsesStreamEvent.Failed -> throw OpenAiRemoteCompactionV2FailureException(event.response.error)
            else -> Unit
        }
    }

    if (compactionCount == 0) {
        throw OpenAiRemoteCompactionV2StreamIncompleteException()
    }
    if (completedCount > 1) {
        throw OpenAiRemoteCompactionV2ProtocolException(
            "Remote compaction v2 expected at most one response.completed event, got $completedCount.",
        )
    }
    if (compactionCount != 1) {
        throw OpenAiRemoteCompactionV2ProtocolException(
            "Remote compaction v2 expected exactly one compaction output item, " +
                "got $compactionCount from $outputItemCount output items.",
        )
    }

    return RemoteCompactionV2Response(
        compactionOutput = checkNotNull(compactionOutput),
        completedResponse = completedResponse,
    )
}

internal suspend fun <T> retryOpenAiStreamingTransport(
    retry: OpenAiClientRetryConfig,
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (cause: Throwable) {
            if (!cause.isRetryableOpenAiTransportException(retry) || retries >= retry.maxRetries) {
                throw cause
            }
            delay(retry.streamingRetryDelayMillis(retries))
            retries += 1
        }
    }
}

private fun OpenAiClientRetryConfig.streamingRetryDelayMillis(retryIndex: Int): Long {
    var delayMillis = baseDelayMillis
    repeat(retryIndex) {
        delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis)
    }
    val randomizedDelayMillis = if (randomizationMillis > 0) {
        delayMillis + Random.nextLong(from = 0, until = randomizationMillis + 1)
    } else {
        delayMillis
    }
    return randomizedDelayMillis.coerceAtMost(maxDelayMillis)
}

public class OpenAiRemoteCompactionV2FailureException(
    public val error: ResponseError?,
) : IllegalStateException(
    error?.message ?: "Remote compaction v2 failed without structured error.",
)

public class OpenAiRemoteCompactionV2ProtocolException(
    message: String,
) : IllegalStateException(message)

public class OpenAiRemoteCompactionV2StreamIncompleteException : IOException(
    "Remote compaction v2 stream closed before compaction output.",
)

internal fun HttpStatusCode.isRetryableOpenAiStatus(retry: OpenAiClientRetryConfig): Boolean =
    when (value) {
        408 -> retry.retryTransport
        429 -> retry.retryRateLimited
        in 500..599 -> retry.retryServerErrors
        else -> false
    }

internal fun Throwable.isRetryableOpenAiTransportException(retry: OpenAiClientRetryConfig): Boolean {
    if (!retry.retryTransport) {
        return false
    }
    val cause = unwrapCancellationException()
    return cause !is CancellationException && cause is IOException
}
