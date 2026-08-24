package io.github.stream29.kodex.openai.client

import io.github.stream29.kodex.openai.CodexAccountUsageResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeRequest
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetCreditsResponse
import io.github.stream29.kodex.openai.CodexTokenUsageProfile
import io.github.stream29.kodex.openai.ImageEditRequest
import io.github.stream29.kodex.openai.ImageGenerationRequest
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiErrorResponse
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiResultSerializer
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.SearchRequest
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.ktorclientext.ChatGptAccountId
import io.github.stream29.kodex.utils.ktorclientext.CodexOriginator
import io.github.stream29.kodex.utils.ktorclientext.OpenAiSearchVersion
import io.github.stream29.kodex.utils.ktorclientext.SseCompatibility
import io.github.stream29.kodex.utils.ktorclientext.addAll
import io.github.stream29.kodex.utils.ktorclientext.postSseEvents
import io.github.stream29.kodex.utils.ktorclientext.set
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

public class OpenAiClient(
    private val authStore: OpenAiAuthStore,
    private val config: OpenAiClientConfig = OpenAiClientConfig(),
) : OpenAiClientContract {
    private val httpClient: HttpClient = HttpClient {
        install(HttpRequestRetry) {
            maxRetries = config.retry.maxRetries
            retryIf { request, response ->
                val state = request.attributes.getOrNull(RemoteCompactionRetryBudgetKey)
                val retryable = response.status.isRetryableOpenAiStatus(config.retry)
                if (retryable && state != null) {
                    state.reserveRetry(
                        termination = "http_status",
                        httpStatus = response.status.value,
                    )
                } else {
                    retryable
                }
            }
            retryOnExceptionIf { request, cause ->
                val state = request.attributes.getOrNull(RemoteCompactionRetryBudgetKey)
                val retryable = cause.isRetryableOpenAiTransportException(config.retry)
                if (retryable && state != null) {
                    state.reserveRetry(
                        termination = "transport_exception",
                    )
                } else {
                    retryable
                }
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
        }
    }

    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> {
        val response = httpClient.get {
            authenticate()
            url {
                appendPathSegments("models")
            }
            accept(ContentType.Application.Json)
            parameter("client_version", config.clientVersion)
        }
        return response.openAiResponseResult(ModelsResponse.serializer())
    }

    override suspend fun getCodexAccountUsage(): OpenAiResponseResult<CodexAccountUsageResponse> {
        val response = httpClient.get(accountEndpoint("wham/usage")) {
            authenticate()
            accept(ContentType.Application.Json)
        }
        return response.httpStatusResponseResult(CodexAccountUsageResponse.serializer())
    }

    override suspend fun listCodexRateLimitResetCredits():
        OpenAiResponseResult<CodexRateLimitResetCreditsResponse> {
        val response = httpClient.get(accountEndpoint("wham/rate-limit-reset-credits")) {
            authenticate()
            accept(ContentType.Application.Json)
        }
        return response.httpStatusResponseResult(CodexRateLimitResetCreditsResponse.serializer())
    }

    override suspend fun consumeCodexRateLimitResetCredit(
        request: CodexRateLimitResetConsumeRequest,
        expectedAccount: OpenAiSubscriptionAuthState,
    ): OpenAiResponseResult<CodexRateLimitResetConsumeResponse> {
        val response = httpClient.post(accountEndpoint("wham/rate-limit-reset-credits/consume")) {
            authenticate(expectedAccount)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.httpStatusResponseResult(CodexRateLimitResetConsumeResponse.serializer())
    }

    override suspend fun getCodexTokenUsageProfile(): OpenAiResponseResult<CodexTokenUsageProfile> {
        val response = httpClient.get(accountEndpoint("wham/profiles/me")) {
            authenticate()
            accept(ContentType.Application.Json)
        }
        return response.httpStatusResponseResult(CodexTokenUsageProfile.serializer())
    }

    override suspend fun createResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent> {
        return httpClient.streamResponseEvents(
            socketTimeoutMillis = config.sseSocketTimeoutMillis,
            retry = config.retry,
        ) {
            authenticate()
            url {
                appendPathSegments("responses")
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun createResponse(
        request: ResponsesApiRequest,
        installationId: String?,
        turnMetadata: String,
        windowId: String,
    ): Flow<ResponsesStreamEvent> {
        return httpClient.streamResponseEvents(
            socketTimeoutMillis = config.sseSocketTimeoutMillis,
            retry = config.retry,
        ) {
            authenticate()
            url {
                appendPathSegments("responses")
            }
            contentType(ContentType.Application.Json)
            installationId?.let { headers[HeaderCodexInstallationId] = it }
            headers[HeaderCodexTurnMetadata] = turnMetadata
            headers[HeaderCodexWindowId] = windowId
            setBody(request)
        }
    }

    override suspend fun createRemoteCompactionV2Response(
        request: ResponsesApiRequest,
        installationId: String?,
        turnMetadata: String,
        windowId: String,
    ): RemoteCompactionV2Response =
        retryOpenAiStreamingTransportWithBudget(
            retry = config.retry.copy(maxRetries = config.remoteCompactionMaxRetries),
        ) { budget ->
            httpClient.streamRemoteCompactionEvents(
                budget = budget,
                socketTimeoutMillis = config.sseSocketTimeoutMillis,
            ) {
                authenticate()
                url {
                    appendPathSegments("responses")
                }
                contentType(ContentType.Application.Json)
                headers[HeaderCodexBetaFeatures] = RemoteCompactionV2Feature
                installationId?.let { headers[HeaderCodexInstallationId] = it }
                headers[HeaderCodexTurnMetadata] = turnMetadata
                headers[HeaderCodexWindowId] = windowId
                setBody(request)
            }.collectRemoteCompactionV2Response()
        }

    override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse> {
        val response = httpClient.post {
            authenticate()
            url {
                appendPathSegments("images", "generations")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.openAiResponseResult(ImageResponse.serializer())
    }

    override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse> {
        val response = httpClient.post {
            authenticate()
            url {
                appendPathSegments("images", "edits")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.openAiResponseResult(ImageResponse.serializer())
    }

    override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse> {
        val response = httpClient.post {
            authenticate()
            url {
                appendPathSegments("alpha", "search")
            }
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            headers[HttpHeaders.OpenAiSearchVersion] = config.clientVersion
            setBody(request)
        }
        return response.openAiResponseResult(SearchResponse.serializer())
    }

    override fun close() {
        httpClient.close()
    }

    private fun accountEndpoint(path: String): String =
        "${config.accountBaseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun HttpRequestBuilder.authenticate(
        expectedAccount: OpenAiSubscriptionAuthState? = null,
    ) {
        val account = when (val state = authStore.state.value) {
            is OpenAiAuthState.Authenticated -> state.credentials
            is OpenAiAuthState.Unavailable -> {
                throw IllegalStateException(state.requestFailureMessage())
            }
        }
        check(expectedAccount == null || account == expectedAccount) {
            "The authenticated OpenAI account changed before the request started."
        }
        bearerAuth(account.accessToken)
        account.accountId
            ?.takeIf(String::isNotBlank)
            ?.let { accountId -> headers[HttpHeaders.ChatGptAccountId] = accountId }
    }
}

private fun OpenAiAuthState.Unavailable.requestFailureMessage(): String =
    "OpenAI authentication is unavailable: " +
        when (this) {
            OpenAiAuthState.Unavailable.NotLoaded -> "credentials have not been loaded."
            OpenAiAuthState.Unavailable.CredentialsNotFound -> "credentials were not found."
            OpenAiAuthState.Unavailable.UnsupportedAuthMode ->
                "the selected credentials use an unsupported authentication mode."
            OpenAiAuthState.Unavailable.InvalidCredentials ->
                "the selected credentials are malformed or incomplete."
            OpenAiAuthState.Unavailable.CredentialSourceUnavailable ->
                "the credential source could not be read."
            OpenAiAuthState.Unavailable.UnexpectedFailure ->
                "credential loading failed unexpectedly."
        }

private const val RemoteCompactionV2Feature: String = "remote_compaction_v2"
private const val HeaderCodexBetaFeatures: String = "x-codex-beta-features"
private const val HeaderCodexInstallationId: String = "x-codex-installation-id"
private const val HeaderCodexTurnMetadata: String = "x-codex-turn-metadata"
private const val HeaderCodexWindowId: String = "x-codex-window-id"

private fun HttpClient.streamResponseEvents(
    socketTimeoutMillis: Long,
    retry: OpenAiClientRetryConfig,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ResponsesStreamEvent> =
    postSseEvents(socketTimeoutMillis, configureRequest)
        .mapNotNull { event -> event.data?.takeIf { it != "[DONE]" } }
        .map { data -> OpenAiJsonCodec.decodeFromString<ResponsesStreamEvent>(data) }
        .catch { cause ->
            if (!cause.isRetryableOpenAiTransportException(retry)) {
                throw cause
            }
        }

internal sealed interface RemoteCompactionStreamEvent {
    data class ResponseEvent(
        val event: ResponsesStreamEvent,
    ) : RemoteCompactionStreamEvent

    data object Done : RemoteCompactionStreamEvent
}

private val RemoteCompactionRetryBudgetKey: AttributeKey<RemoteCompactionRetryBudget> =
    AttributeKey("RemoteCompactionRetryBudget")

private val RemoteCompactionLogger: KLogger by lazy {
    KotlinLogging.logger {}
}

internal class RemoteCompactionRetryBudget(
    private val retry: OpenAiClientRetryConfig,
    private val logger: KLogger = RemoteCompactionLogger,
) {
    public val operationId: String = Random.nextLong().toString()
    public var retries: Int = 0
        private set
    private var lastEvent: String = "none"
    private var lastHttpStatus: Int? = null
    private var reservedRetryDelayMillis: Long? = null
    private val operationStartedAt = TimeSource.Monotonic.markNow()
    private var attemptStartedAt = operationStartedAt

    fun beginAttempt() {
        attemptStartedAt = TimeSource.Monotonic.markNow()
        lastEvent = "none"
        lastHttpStatus = null
        reservedRetryDelayMillis = null
    }

    fun recordEvent(event: String) {
        lastEvent = event
    }

    fun reserveRetry(
        termination: String,
        httpStatus: Int? = null,
        retry: OpenAiClientRetryConfig = this.retry,
    ): Boolean {
        if (retries >= retry.maxRetries) return false
        lastHttpStatus = httpStatus
        val retryIndex = retries
        retries += 1
        val reservedDelayMillis = nextDelayMillis(retryIndex, retry)
        reservedRetryDelayMillis = reservedDelayMillis
        logger.warn {
            diagnosticMessage(
                attempt = retryIndex + 1,
                retry = retry,
                termination = termination,
                nextDelayMillis = reservedDelayMillis,
            )
        }
        return true
    }

    fun nextDelayMillis(
        retryIndex: Int = retries - 1,
        retry: OpenAiClientRetryConfig = this.retry,
    ): Long {
        reservedRetryDelayMillis?.let {
            reservedRetryDelayMillis = null
            return it
        }
        var delayMillis = retry.baseDelayMillis
        repeat(retryIndex.coerceAtLeast(0)) {
            delayMillis = (delayMillis * 2).coerceAtMost(retry.maxDelayMillis)
        }
        val randomizedDelayMillis = if (retry.randomizationMillis > 0) {
            delayMillis + Random.nextLong(from = 0, until = retry.randomizationMillis + 1)
        } else {
            delayMillis
        }
        return randomizedDelayMillis.coerceAtMost(retry.maxDelayMillis)
    }

    fun logSuccess() {
        logger.debug {
            diagnosticMessage(
                attempt = retries + 1,
                retry = retry,
                termination = "response.completed",
            )
        }
    }

    fun logFinalFailure(termination: String, retryable: Boolean) {
        logger.warn {
            diagnosticMessage(
                attempt = retries + 1,
                retry = retry,
                termination = termination,
                retryable = retryable,
            )
        }
    }

    private fun diagnosticMessage(
        attempt: Int,
        retry: OpenAiClientRetryConfig,
        termination: String,
        nextDelayMillis: Long? = null,
        retryable: Boolean? = null,
    ): String = buildString {
        append("operation=remote_compaction_v2 ")
        append("operation_id=").append(operationId).append(' ')
        append("attempt=").append(attempt).append('/').append(retry.maxRetries + 1).append(' ')
        append("attempt_duration_ms=").append(attemptStartedAt.elapsedNow().inWholeMilliseconds).append(' ')
        append("cumulative_duration_ms=").append(operationStartedAt.elapsedNow().inWholeMilliseconds).append(' ')
        append("termination=").append(termination).append(' ')
        append("last_event=").append(lastEvent).append(' ')
        append("http_status=").append(lastHttpStatus ?: "-")
        nextDelayMillis?.let { append(" next_delay_ms=").append(it) }
        retryable?.let { append(" retryable=").append(it) }
    }
}

private fun HttpClient.streamRemoteCompactionEvents(
    budget: RemoteCompactionRetryBudget,
    socketTimeoutMillis: Long,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<RemoteCompactionStreamEvent> =
    postSseEvents(socketTimeoutMillis) {
        configureRequest()
        attributes.put(RemoteCompactionRetryBudgetKey, budget)
    }.mapNotNull { event ->
        val data = event.data ?: return@mapNotNull null
        if (data == "[DONE]") {
            budget.recordEvent("done")
            RemoteCompactionStreamEvent.Done
        } else {
            val responseEvent = OpenAiJsonCodec.decodeFromString<ResponsesStreamEvent>(data)
            budget.recordEvent(responseEvent::class.simpleName ?: "unknown")
            RemoteCompactionStreamEvent.ResponseEvent(responseEvent)
        }
    }

internal suspend fun Flow<RemoteCompactionStreamEvent>.collectRemoteCompactionV2Response(
): RemoteCompactionV2Response {
    var outputItemCount = 0
    var compactionCount = 0
    var compactionOutput: ResponseItem.Compaction? = null
    var completedResponse: Response? = null

    onEach { streamEvent ->
        when (streamEvent) {
            RemoteCompactionStreamEvent.Done -> {
                throw OpenAiRemoteCompactionV2StreamIncompleteException()
            }

            is RemoteCompactionStreamEvent.ResponseEvent -> when (val event = streamEvent.event) {
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
                    if (compactionCount != 1) {
                        throw OpenAiRemoteCompactionV2ProtocolException(
                            "Remote compaction v2 expected exactly one compaction output item " +
                                "before response.completed, got $compactionCount from " +
                                "$outputItemCount output items.",
                        )
                    }
                    completedResponse = event.response
                }

                is ResponsesStreamEvent.Failed -> {
                    throw OpenAiRemoteCompactionV2StreamFailureException()
                }

                is ResponsesStreamEvent.Incomplete -> {
                    throw OpenAiRemoteCompactionV2StreamFailureException()
                }

                else -> Unit
            }
        }
    }.takeWhile { completedResponse == null }.collect()

    if (completedResponse == null) {
        throw OpenAiRemoteCompactionV2StreamIncompleteException()
    }

    return RemoteCompactionV2Response(
        compactionOutput = checkNotNull(compactionOutput),
        completedResponse = completedResponse,
    )
}

private suspend fun <Success> HttpResponse.openAiResponseResult(
    successSerializer: KSerializer<Success>,
): OpenAiResponseResult<Success> =
    decodeOpenAiResponseResult(
        status = status,
        payload = body<JsonElement>(),
        successSerializer = successSerializer,
    )

private suspend fun <Success> HttpResponse.httpStatusResponseResult(
    successSerializer: KSerializer<Success>,
): OpenAiResponseResult<Success> =
    decodeHttpStatusResponseResult(
        status = status,
        payload = body<JsonElement>(),
        successSerializer = successSerializer,
    )

internal fun <Success> decodeHttpStatusResponseResult(
    status: HttpStatusCode,
    payload: JsonElement,
    successSerializer: KSerializer<Success>,
): OpenAiResponseResult<Success> {
    if (status.value !in 200..299) {
        return OpenAiResult.Failure(
            OpenAiJsonCodec.decodeFromJsonElement(OpenAiErrorResponse.serializer(), payload),
        )
    }
    return OpenAiResult.Success(
        OpenAiJsonCodec.decodeFromJsonElement(successSerializer, payload),
    )
}

internal fun <Success> decodeOpenAiResponseResult(
    status: HttpStatusCode,
    payload: JsonElement,
    successSerializer: KSerializer<Success>,
): OpenAiResponseResult<Success> {
    if (status.value !in 200..299) {
        return OpenAiResult.Failure(
            OpenAiJsonCodec.decodeFromJsonElement(OpenAiErrorResponse.serializer(), payload),
        )
    }
    return OpenAiJsonCodec.decodeFromJsonElement(
        OpenAiResultSerializer(successSerializer, OpenAiErrorResponse.serializer()),
        payload,
    )
}

internal suspend fun <T> retryOpenAiStreamingTransport(
    retry: OpenAiClientRetryConfig,
    block: suspend () -> T,
): T = retryOpenAiStreamingTransportWithBudget(retry) { block() }

internal suspend fun <T> retryOpenAiStreamingTransportWithBudget(
    retry: OpenAiClientRetryConfig,
    block: suspend (RemoteCompactionRetryBudget) -> T,
): T {
    val budget = RemoteCompactionRetryBudget(retry)
    suspend fun runAttempts(): T {
        while (true) {
            budget.beginAttempt()
            try {
                return block(budget).also { budget.logSuccess() }
            } catch (cause: Throwable) {
                if (cause is CancellationException) {
                    throw cause
                }
                val retryable = cause.isRetryableOpenAiStreamingException(retry)
                val termination = cause.remoteCompactionTermination()
                if (!retryable) {
                    budget.logFinalFailure(termination, retryable = false)
                    throw cause
                }
                if (!budget.reserveRetry(termination)) {
                    budget.logFinalFailure(termination, retryable = true)
                    throw cause
                }
                delay(budget.nextDelayMillis().milliseconds)
            }
        }
    }

    return runAttempts()
}

public class OpenAiRemoteCompactionV2ProtocolException(
    message: String,
) : IllegalStateException(message)

public class OpenAiRemoteCompactionV2StreamIncompleteException : IOException(
    "Remote compaction v2 stream closed before compaction output.",
)

public class OpenAiRemoteCompactionV2StreamFailureException : IOException(
    "Remote compaction v2 stream reported a retryable failure.",
)

private fun Throwable.remoteCompactionTermination(): String = when (this) {
    is OpenAiRemoteCompactionV2ProtocolException -> "protocol_error"
    is OpenAiRemoteCompactionV2StreamIncompleteException -> "stream_incomplete"
    is OpenAiRemoteCompactionV2StreamFailureException -> "stream_failed"
    else -> "transport_error"
}

private fun Throwable.isRetryableOpenAiStreamingException(
    retry: OpenAiClientRetryConfig,
): Boolean {
    val cause = openAiRootCause()
    val responseException = cause as? io.ktor.client.plugins.ResponseException
    if (responseException != null) {
        return responseException.response.status.isRetryableOpenAiStatus(retry)
    }
    return cause.isRetryableOpenAiTransportException(retry)
}

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
    val cause = openAiRootCause()
    return cause !is CancellationException && cause is IOException
}

private fun Throwable.openAiRootCause(): Throwable {
    var current = unwrapCancellationException()
    while (current is SSEClientException) {
        val nested = current.cause ?: break
        current = nested.unwrapCancellationException()
    }
    return current
}
