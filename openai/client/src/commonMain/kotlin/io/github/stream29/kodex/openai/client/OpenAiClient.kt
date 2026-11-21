@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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
import io.github.stream29.kodex.openai.OpenAiFailureDiagnostic
import io.github.stream29.kodex.openai.codexTurnClientMetadataOrNull
import io.github.stream29.kodex.openai.escapeOpenAiDiagnosticText
import io.github.stream29.kodex.openai.OpenAiFailureTermination
import io.github.stream29.kodex.openai.OpenAiFailureReason
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiResultSerializer
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseInclude
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.SearchRequest
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.TextControls
import io.github.stream29.kodex.openai.ToolChoice
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.unavailableMessage
import io.github.stream29.kodex.openai.client.contract.ObservedResponseEvent
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseObservationSource
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.ktorclientext.ChatGptAccountId
import io.github.stream29.kodex.utils.ktorclientext.CodexOriginator
import io.github.stream29.kodex.utils.ktorclientext.OpenAiSearchVersion
import io.github.stream29.kodex.utils.ktorclientext.SseCompatibility
import io.github.stream29.kodex.utils.ktorclientext.postSseEventsWithHeaders
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.core.remaining
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.InternalIoApi
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val OpenAiStreamingRequestKey = AttributeKey<Unit>("OpenAiStreamingRequest")
internal const val MaxOpenAiErrorBodyBytes: Long = 1024 * 1024
private const val MaxOpenAiErrorJsonDepth: Int = 64

// This interceptor runs inside HttpRequestRetry, where the response body is
// still available before a retry decision. Bound the error body before
// classifying it, and replay it for Ktor's ordinary error handling.
@OptIn(InternalAPI::class)
private val OpenAiPolicyGuard = createClientPlugin("OpenAiPolicyGuard") {
    on(Send) { request ->
        request.attributes.getOrNull(RemoteCompactionRetryBudgetKey)?.beginHttpAttempt()
        val call = try {
            proceed(request)
        } catch (cause: Throwable) {
            currentCoroutineContext().ensureActive()
            if (cause is CancellationException) throw cause
            val response = cause.openAiHttpResponse()
            if (
                request.attributes.contains(OpenAiStreamingRequestKey) &&
                response != null && response.status.value !in 200..299
            ) {
                val diagnostic = response.httpFailureDiagnostic()
                if (diagnostic?.reason == OpenAiFailureReason.ContentPolicyViolation) {
                    val failure = OpenAiModerationBlockedException(OpenAiFailureReason.ContentPolicyViolation)
                    failure.failureDiagnostic = diagnostic.copy(
                        termination = OpenAiFailureTermination.ModerationBlocked,
                        reason = OpenAiFailureReason.ContentPolicyViolation,
                        httpStatus = response.status.value,
                        requestId = response.providerRequestId(),
                    )
                    throw failure
                }
            }
            throw cause
        }
        if (!request.attributes.contains(OpenAiStreamingRequestKey) || call.response.status.value in 200..299) {
            call
        } else {
            val response = call.response
            val content = response.rawContent
            val bytes = content.readBoundedErrorBody()
            if (bytes.size.toLong() > MaxOpenAiErrorBodyBytes) {
                content.cancel()
                throw OpenAiHttpStreamException(response.status.value, response.providerRequestId())
            }
            val errorBody = bytes.decodeToString()
            if (!errorBody.hasSafeJsonDepth()) {
                throw OpenAiHttpStreamException(response.status.value, response.providerRequestId())
            }
            val diagnostic = errorBody.httpFailureDiagnostic()
            if (diagnostic?.reason == OpenAiFailureReason.ContentPolicyViolation) {
                val failure = OpenAiModerationBlockedException(OpenAiFailureReason.ContentPolicyViolation)
                failure.failureDiagnostic = diagnostic.copy(
                    termination = OpenAiFailureTermination.ModerationBlocked,
                    reason = OpenAiFailureReason.ContentPolicyViolation,
                    httpStatus = response.status.value,
                    requestId = response.providerRequestId(),
                )
                throw failure
            }
            call.replaceResponse { ByteReadChannel(bytes) }
        }
    }
}

@OptIn(InternalAPI::class, InternalIoApi::class)
internal suspend fun ByteReadChannel.readBoundedErrorBody(): ByteArray {
    val buffer = Buffer()
    while (buffer.size <= MaxOpenAiErrorBodyBytes && awaitContent()) {
        val count = minOf(readBuffer.remaining, MaxOpenAiErrorBodyBytes + 1 - buffer.size)
        readBuffer.readTo(buffer, count)
    }
    return buffer.readByteArray()
}

private fun HttpResponse.providerRequestId(): String? =
    (headers["x-oai-request-id"] ?: headers["x-request-id"])?.takeIf { it.isNotEmpty() }

internal fun HttpClientConfig<*>.installOpenAiRetry(retry: OpenAiClientRetryConfig) {
    install(HttpRequestRetry) {
        maxRetries = retry.maxRetries
        retryIf { request, response ->
            val state = request.attributes.getOrNull(RemoteCompactionRetryBudgetKey)
            val retryable = response.status.isRetryableOpenAiStatus(retry)
            if (retryable && state != null) {
                state.reserveRetry(termination = "http_status", httpStatus = response.status.value)
            } else {
                retryable
            }
        }
        retryOnExceptionIf { request, cause ->
            val state = request.attributes.getOrNull(RemoteCompactionRetryBudgetKey)
            val retryable = cause.isRetryableOpenAiException(retry)
            if (retryable && state != null) {
                state.reserveRetry(termination = "transport_exception")
            } else {
                retryable
            }
        }
        exponentialDelay(
            baseDelayMs = retry.baseDelayMillis,
            maxDelayMs = retry.maxDelayMillis,
            randomizationMs = retry.randomizationMillis,
        )
    }
    install(OpenAiPolicyGuard)
}

internal fun HttpClientConfig<*>.configureOpenAiClient(config: OpenAiClientConfig) {
    installOpenAiRetry(config.retry)
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
    install(SSE)
    install(SseCompatibility)
    defaultRequest {
        url(config.baseUrl.trimEnd('/') + "/")
        headers[HttpHeaders.CodexOriginator] = config.originator
        headers[HttpHeaders.UserAgent] = config.userAgent
    }
}

public actual class OpenAiClient actual constructor(
    private val authStore: OpenAiAuthStore,
    private val config: OpenAiClientConfig,
) : OpenAiClientContract {
    private val httpClient: HttpClient = HttpClient { configureOpenAiClient(config) }

    actual override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> {
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

    actual override suspend fun getCodexAccountUsage(): OpenAiResponseResult<CodexAccountUsageResponse> {
        val response = httpClient.get(accountEndpoint("wham/usage")) {
            authenticate()
            accept(ContentType.Application.Json)
        }
        return response.httpStatusResponseResult(CodexAccountUsageResponse.serializer())
    }

    actual override suspend fun listCodexRateLimitResetCredits():
        OpenAiResponseResult<CodexRateLimitResetCreditsResponse> {
        val response = httpClient.get(accountEndpoint("wham/rate-limit-reset-credits")) {
            authenticate()
            accept(ContentType.Application.Json)
        }
        return response.httpStatusResponseResult(CodexRateLimitResetCreditsResponse.serializer())
    }

    actual override suspend fun consumeCodexRateLimitResetCredit(
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

    actual override suspend fun getCodexTokenUsageProfile(): OpenAiResponseResult<CodexTokenUsageProfile> {
        val response = httpClient.get(accountEndpoint("wham/profiles/me")) {
            authenticate()
            accept(ContentType.Application.Json)
        }
        return response.httpStatusResponseResult(CodexTokenUsageProfile.serializer())
    }

    actual override suspend fun createResponse(
        model: OpenAiModelId,
        input: List<ResponseItem>,
        instructions: String,
        store: Boolean,
        previousResponseId: String?,
        tools: List<ToolSpec>,
        toolChoice: ToolChoice,
        parallelToolCalls: Boolean,
        reasoning: Reasoning,
        include: Set<ResponseInclude>,
        serviceTier: ServiceTier,
        promptCacheKey: String?,
        text: TextControls,
        installationId: String?,
        sessionId: String?,
        threadId: String?,
        turnId: String?,
        windowId: String?,
        threadSource: String?,
        turnTrigger: String?,
        turnState: String?,
        onResponseHeaders: suspend (OpenAiResponseHeaders) -> Unit,
    ): Flow<ResponsesStreamEvent> {
        val request = ResponsesApiRequest(
            model = model,
            input = input,
            instructions = instructions,
            store = store,
            previousResponseId = previousResponseId,
            tools = tools,
            toolChoice = toolChoice,
            parallelToolCalls = parallelToolCalls,
            reasoning = reasoning,
            include = include,
            serviceTier = serviceTier,
            promptCacheKey = promptCacheKey ?: threadId,
            text = text,
            clientMetadata = codexTurnClientMetadataOrNull(
                json = OpenAiJsonCodec,
                installationId = installationId,
                sessionId = sessionId,
                threadId = threadId,
                turnId = turnId,
                windowId = windowId,
                threadSource = threadSource,
                turnTrigger = turnTrigger,
                turnState = turnState,
            ),
        )
        return httpClient.streamResponseEvents(
            socketTimeoutMillis = config.sseSocketTimeoutMillis,
            retry = config.retry,
            onResponseHeaders = onResponseHeaders,
        ) {
            authenticate()
            url {
                appendPathSegments("responses")
            }
            contentType(ContentType.Application.Json)
            request.clientMetadata?.installationId?.let { headers[HeaderCodexInstallationId] = it }
            request.clientMetadata?.turnMetadata?.let { headers[HeaderCodexTurnMetadata] = it }
            request.clientMetadata?.windowId?.let { headers[HeaderCodexWindowId] = it }
            turnState?.let { headers[HeaderCodexTurnState] = it }
            ultrafastRouting(request)
            setBody(request)
        }
    }

    actual override suspend fun createRemoteCompactionV2Response(
        request: ResponsesApiRequest,
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
                request.clientMetadata?.installationId?.let { headers[HeaderCodexInstallationId] = it }
                request.clientMetadata?.turnMetadata?.let { headers[HeaderCodexTurnMetadata] = it }
                request.clientMetadata?.windowId?.let { headers[HeaderCodexWindowId] = it }
                ultrafastRouting(request)
                setBody(request)
            }.collectRemoteCompactionV2Response()
        }

    actual override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse> {
        val response = httpClient.post {
            imageRequestTimeout()
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

    actual override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse> {
        val response = httpClient.post {
            imageRequestTimeout()
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

    actual override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse> {
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

    actual override fun close() {
        httpClient.close()
    }

    private fun accountEndpoint(path: String): String =
        "${config.accountBaseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun HttpRequestBuilder.imageRequestTimeout() {
        timeout {
            requestTimeoutMillis = config.imageRequestTimeoutMillis
            socketTimeoutMillis = config.imageRequestTimeoutMillis
        }
    }

    private fun HttpRequestBuilder.authenticate(
        expectedAccount: OpenAiSubscriptionAuthState? = null,
    ) {
        val account = when (val state = authStore.state.value) {
            is OpenAiAuthState.Authenticated -> state.credentials
            is OpenAiAuthState.Unavailable -> {
                throw IllegalStateException(state.unavailableMessage())
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

private const val RemoteCompactionV2Feature: String = "remote_compaction_v2"
/** Request-local routing from the exact body snapshot, never from live settings. */
private fun HttpRequestBuilder.ultrafastRouting(request: ResponsesApiRequest) {
    if (request.serviceTier == ServiceTier.Ultrafast) {
        headers["x-codex-routing-hint"] = "model=${request.model.value};tier=${request.serviceTier.requestValue}"
    }
}

private const val HeaderCodexBetaFeatures: String = "x-codex-beta-features"
private const val HeaderCodexInstallationId: String = "x-codex-installation-id"
private const val HeaderCodexTurnMetadata: String = "x-codex-turn-metadata"
private const val HeaderCodexWindowId: String = "x-codex-window-id"
private const val HeaderCodexTurnState: String = "x-codex-turn-state"

@OptIn(ExperimentalCoroutinesApi::class)
internal fun HttpClient.streamResponseEvents(
    socketTimeoutMillis: Long,
    retry: OpenAiClientRetryConfig,
    onResponseHeaders: suspend (OpenAiResponseHeaders) -> Unit = {},
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ResponsesStreamEvent> {
    val observed = streamObservedResponseEvents(socketTimeoutMillis, retry, onResponseHeaders, configureRequest = configureRequest)
    val wire = observed.map { it.event }
    return object : Flow<ResponsesStreamEvent> by wire, OpenAiResponseObservationSource {
        override val eventsWithObservations = observed
    }
}

/** Ordinary Responses events plus client read observations, before storage-level estimation. */
internal fun HttpClient.streamObservedResponseEvents(
    socketTimeoutMillis: Long,
    retry: OpenAiClientRetryConfig,
    onResponseHeaders: suspend (OpenAiResponseHeaders) -> Unit = {},
    clock: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<ObservedResponseEvent> =
    flow {
        var acceptedStatus: Int? = null
        var providerRequestId: String? = null
        var observedResponseId: String? = null
        val observation = ResponseAttemptObservation()
        emitAll(
            postSseEventsWithHeaders(
                socketTimeoutMillis = socketTimeoutMillis,
                onResponseStatus = { acceptedStatus = it.value },
                onResponseHeaders = { headers ->
                    val requestId = headers["x-oai-request-id"] ?: headers["x-request-id"]
                    providerRequestId = requestId?.takeIf { it.isNotEmpty() }
                    onResponseHeaders(
                        OpenAiResponseHeaders(
                            turnState = headers[HeaderCodexTurnState],
                            requestId = requestId,
                            httpStatus = acceptedStatus,
                        ),
                    )
                },
                transformOnRead = { frame -> ReceivedResponseFrame(clock.markNow(), frame.data) },
                configureRequest = {
                    configureRequest()
                    attributes.put(OpenAiStreamingRequestKey, Unit)
                },
            )
                .mapNotNull { frame ->
                    val data = frame.data ?: return@mapNotNull null
                    if (data == "[DONE]") {
                        observation.endAttempt()
                        return@mapNotNull null
                    }
                    val decoded = decodeObservedResponseEvent(data)
                    val event = decoded.event
                    val responseId = event.responseIdentifier()
                    if (!responseId.isNullOrEmpty() && responseId != observedResponseId) {
                        observedResponseId = responseId
                    }
                    event.moderationFailureOrNull(
                        httpStatus = acceptedStatus,
                        requestId = providerRequestId,
                        responseId = observedResponseId?.takeIf { it.isNotEmpty() },
                    )?.let { throw it }
                    observation.observe(decoded, frame.receivedAt)
                }
                .catch { cause ->
                    currentCoroutineContext().ensureActive()
                    if (cause is CancellationException) throw cause
                    val root = cause.openAiRootCause()
                    if (root is CancellationException) throw root
                    if (cause !is Exception) throw cause
                    if (cause is OpenAiModerationBlockedException) throw cause
                    if (root is OpenAiModerationBlockedException) throw root
                    if (root is OpenAiHttpStreamException) throw root
                    val status = cause.openAiHttpStatus()
                    val httpError = status != null && status !in 200..299
                    val errorRequestId = cause.openAiHttpResponse()?.headers?.let { headers ->
                        headers["x-oai-request-id"] ?: headers["x-request-id"]
                    }
                    if (httpError) providerRequestId = errorRequestId?.takeIf { it.isNotEmpty() }
                    val httpDiagnostic = if (httpError) cause.httpFailureDiagnostic() else null
                    val httpReason = httpDiagnostic?.reason
                    if (httpReason == OpenAiFailureReason.ContentPolicyViolation) {
                        val failure = OpenAiModerationBlockedException(OpenAiFailureReason.ContentPolicyViolation)
                        failure.failureDiagnostic = httpDiagnostic.copy(
                            termination = OpenAiFailureTermination.ModerationBlocked,
                            reason = OpenAiFailureReason.ContentPolicyViolation,
                            httpStatus = status,
                            requestId = providerRequestId,
                        )
                        throw failure
                    }
                    if (cause.isRetryableOpenAiException(retry)) {
                        onResponseHeaders(
                            OpenAiResponseHeaders(
                                turnState = null,
                                requestId = null,
                                failure = (httpDiagnostic ?: OpenAiFailureDiagnostic(
                                    termination = if (httpError) {
                                        OpenAiFailureTermination.HttpError
                                    } else {
                                        OpenAiFailureTermination.TransportError
                                    },
                                    httpStatus = status ?: acceptedStatus,
                                )).copy(
                                    httpStatus = status ?: acceptedStatus,
                                    requestId = providerRequestId,
                                    responseId = observedResponseId?.takeIf { it.isNotEmpty() },
                                ),
                            ),
                        )
                        return@catch
                    }
                    if (httpError) {
                        val failure = OpenAiHttpStreamException(status, providerRequestId)
                        failure.failureDiagnostic = (httpDiagnostic ?: failure.failureDiagnostic)?.copy(requestId = providerRequestId)
                        throw failure
                    }
                    val failure = OpenAiStreamException(status ?: acceptedStatus)
                    failure.failureDiagnostic = failure.failureDiagnostic?.copy(
                        requestId = providerRequestId,
                        responseId = observedResponseId?.takeIf { it.isNotEmpty() },
                    )
                    throw failure
                },
        )
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
    val operationId: Long = Random.nextLong()
    var retries: Int = 0
        private set
    private var lastEvent: String = "none"
    private var lastHttpStatus: Int? = null
    private var lastFailure: OpenAiFailureDiagnostic? = null
    private var responseId: String? = null
    private var requestId: String? = null
    private var reservedRetryDelayMillis: Long? = null
    private val operationStartedAt = TimeSource.Monotonic.markNow()
    private var attemptStartedAt = operationStartedAt

    fun beginAttempt() {
        attemptStartedAt = TimeSource.Monotonic.markNow()
        lastEvent = "none"
        lastHttpStatus = null
        lastFailure = null
        responseId = null
        requestId = null
        reservedRetryDelayMillis = null
    }

    fun beginHttpAttempt() {
        lastHttpStatus = null
        lastFailure = null
        requestId = null
        responseId = null
    }

    fun recordEvent(event: String) {
        lastEvent = event
    }

    fun recordFailure(diagnostic: OpenAiFailureDiagnostic?) {
        if (diagnostic != null) lastFailure = diagnostic
    }

    fun clearFailure() {
        lastFailure = null
    }

    fun recordRequestId(value: String?) {
        requestId = value?.takeIf { it.isNotEmpty() }
    }

    fun recordResponseId(value: String?) {
        if (!value.isNullOrEmpty() && value != responseId) {
            responseId = value
        }
    }

    fun recordHttpStatus(status: Int) {
        lastHttpStatus = status
    }

    fun attachFailure(failure: Throwable): Throwable {
        val diagnostic = lastFailure?.copy(
            httpStatus = lastHttpStatus,
            requestId = requestId,
            responseId = responseId,
        ) ?: OpenAiFailureDiagnostic(
            termination = if (failure is OpenAiRemoteCompactionV2StreamIncompleteException) {
                OpenAiFailureTermination.MissingTerminal
            } else if (lastHttpStatus != null) {
                OpenAiFailureTermination.HttpError
            } else {
                OpenAiFailureTermination.StreamError
            },
            httpStatus = lastHttpStatus,
            requestId = requestId,
            responseId = responseId,
        )
        when (failure) {
            is OpenAiRemoteCompactionV2StreamIncompleteException -> {
                failure.failureDiagnostic = diagnostic
                failure.retries = retries
                failure.operationId = operationId
            }
            is OpenAiRemoteCompactionV2StreamFailureException -> {
                failure.failureDiagnostic = diagnostic
                failure.retries = retries
                failure.operationId = operationId
            }
            is OpenAiModerationBlockedException -> {
                failure.failureDiagnostic = (lastFailure ?: failure.failureDiagnostic)?.copy(
                    termination = OpenAiFailureTermination.ModerationBlocked,
                    reason = failure.failureDiagnostic?.reason,
                    httpStatus = lastHttpStatus,
                    requestId = requestId,
                    responseId = responseId,
                )
                failure.retries = retries
                failure.operationId = operationId
            }
            is OpenAiHttpStreamException -> {
                failure.failureDiagnostic = (lastFailure ?: failure.failureDiagnostic)?.copy(
                    requestId = requestId,
                    responseId = responseId,
                )
                failure.retries = retries
                failure.operationId = operationId
            }
            is OpenAiRetryableStreamException -> {
                failure.failureDiagnostic = failure.failureDiagnostic?.copy(
                    httpStatus = lastHttpStatus,
                    requestId = requestId,
                    responseId = responseId,
                )
                failure.retries = retries
                failure.operationId = operationId
            }
            is OpenAiStreamException -> {
                failure.failureDiagnostic = failure.failureDiagnostic?.copy(
                    httpStatus = lastHttpStatus,
                    requestId = requestId,
                    responseId = responseId,
                )
                failure.retries = retries
                failure.operationId = operationId
            }
        }
        return failure
    }

    fun reserveRetry(
        termination: String,
        httpStatus: Int? = null,
        retry: OpenAiClientRetryConfig = this.retry,
    ): Boolean {
        if (httpStatus != null) lastHttpStatus = httpStatus
        if (retries >= retry.maxRetries) return false
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
        append(" request_id=").append(requestId?.let(::escapeOpenAiDiagnosticText) ?: "-")
        append(" response_id=").append(responseId?.let(::escapeOpenAiDiagnosticText) ?: "-")
        lastFailure?.let {
            append(" failure_termination=").append(it.termination)
            append(" reason=").append(it.reason ?: "-")
        }
        nextDelayMillis?.let { append(" next_delay_ms=").append(it) }
        retryable?.let { append(" retryable=").append(it) }
    }
}

internal fun HttpClient.streamRemoteCompactionEvents(
    budget: RemoteCompactionRetryBudget,
    socketTimeoutMillis: Long,
    configureRequest: HttpRequestBuilder.() -> Unit,
): Flow<RemoteCompactionStreamEvent> =
    postSseEventsWithHeaders(
        socketTimeoutMillis = socketTimeoutMillis,
        onResponseHeaders = { headers ->
            budget.recordRequestId(headers["x-oai-request-id"] ?: headers["x-request-id"])
        },
        onResponseStatus = { budget.recordHttpStatus(it.value) },
        configureRequest = {
            configureRequest()
            attributes.put(OpenAiStreamingRequestKey, Unit)
            attributes.put(RemoteCompactionRetryBudgetKey, budget)
        },
    ).mapNotNull { event ->
        val data = event.data ?: return@mapNotNull null
        if (data == "[DONE]") {
            budget.recordEvent("done")
            RemoteCompactionStreamEvent.Done
        } else {
            val responseEvent = try {
                OpenAiJsonCodec.decodeFromString<ResponsesStreamEvent>(data)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                throw OpenAiRemoteCompactionV2ProtocolException("Remote compaction v2 received an invalid event.")
            }
            budget.recordEvent(responseEvent::class.simpleName ?: "unknown")
            budget.recordResponseId(responseEvent.responseIdentifier())
            if (responseEvent is ResponsesStreamEvent.Completed) budget.clearFailure()
            budget.recordFailure(responseEvent.toFailureDiagnostic())
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
                    event.moderationFailureOrNull()?.let { throw it }
                    throw OpenAiRemoteCompactionV2StreamFailureException()
                }

                is ResponsesStreamEvent.Incomplete -> {
                    event.moderationFailureOrNull()?.let { throw it }
                    throw OpenAiRemoteCompactionV2StreamFailureException()
                }

                else -> {
                    event.moderationFailureOrNull()?.let { throw it }
                }
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
                currentCoroutineContext().ensureActive()
                if (cause is CancellationException) throw cause
                val root = cause.openAiRootCause()
                if (root is CancellationException) throw root
                if (cause !is Exception) throw cause
                if (root is OpenAiModerationBlockedException) {
                    root.failureDiagnostic?.httpStatus?.let(budget::recordHttpStatus)
                    root.failureDiagnostic?.requestId?.let(budget::recordRequestId)
                    budget.recordResponseId(root.failureDiagnostic?.responseId)
                    budget.recordFailure(root.failureDiagnostic)
                    budget.logFinalFailure("moderation_blocked", retryable = false)
                    throw budget.attachFailure(root)
                }
                if (root is OpenAiHttpStreamException) {
                    budget.recordHttpStatus(root.status)
                    root.failureDiagnostic?.requestId?.let(budget::recordRequestId)
                    budget.recordFailure(root.failureDiagnostic)
                    budget.logFinalFailure("http_error", retryable = false)
                    throw budget.attachFailure(root)
                }
                val retryable = cause.isRetryableOpenAiException(retry)
                val termination = cause.remoteCompactionTermination()
                val httpStatus = cause.openAiHttpStatus()
                val httpError = httpStatus != null && httpStatus !in 200..299
                cause.openAiHttpResponse()?.headers?.let { headers ->
                    budget.recordRequestId(headers["x-oai-request-id"] ?: headers["x-request-id"])
                }
                val httpDiagnostic = if (httpError) cause.httpFailureDiagnostic() else null
                val httpReason = httpDiagnostic?.reason
                if (httpError) {
                    budget.recordFailure(
                        httpDiagnostic ?: OpenAiFailureDiagnostic(OpenAiFailureTermination.HttpError, httpStatus = httpStatus),
                    )
                }
                if (httpError && httpReason == OpenAiFailureReason.ContentPolicyViolation) {
                    budget.recordHttpStatus(httpStatus)
                    val failure = OpenAiModerationBlockedException(OpenAiFailureReason.ContentPolicyViolation)
                    budget.logFinalFailure("moderation_blocked", retryable = false)
                    throw budget.attachFailure(failure)
                }
                if (!retryable) {
                    if (httpStatus != null) budget.recordHttpStatus(httpStatus)
                    val failure = when {
                        httpError -> OpenAiHttpStreamException(httpStatus)
                        cause is OpenAiModerationBlockedException ||
                            cause is OpenAiRemoteCompactionV2ProtocolException -> cause
                        else -> OpenAiStreamException()
                    }
                    if (failure is OpenAiModerationBlockedException) {
                        budget.recordFailure(failure.failureDiagnostic)
                    }
                    budget.logFinalFailure(termination, retryable = false)
                    throw budget.attachFailure(failure)
                }
                if (!budget.reserveRetry(termination, httpStatus)) {
                    budget.logFinalFailure(termination, retryable = true)
                    if (httpError) {
                        throw budget.attachFailure(OpenAiHttpStreamException(httpStatus))
                    }
                    if (cause !is OpenAiRemoteCompactionV2StreamFailureException &&
                        cause !is OpenAiRemoteCompactionV2StreamIncompleteException
                    ) {
                        throw budget.attachFailure(
                            OpenAiRetryableStreamException(
                                OpenAiFailureDiagnostic(OpenAiFailureTermination.TransportError, httpStatus = httpStatus),
                            ),
                        )
                    }
                    throw budget.attachFailure(cause)
                }
                delay(budget.nextDelayMillis().milliseconds)
            }
        }
    }

    return runAttempts()
}

private fun Throwable.remoteCompactionTermination(): String = when (this) {
    is OpenAiRemoteCompactionV2ProtocolException -> "protocol_error"
    is OpenAiRemoteCompactionV2StreamIncompleteException -> "stream_incomplete"
    is OpenAiRemoteCompactionV2StreamFailureException -> "stream_failed"
    is OpenAiModerationBlockedException -> "moderation_blocked"
    else -> if (openAiHttpStatus()?.let { it !in 200..299 } == true) "http_status" else "transport_error"
}

private fun ResponsesStreamEvent.moderationFailureOrNull(
    httpStatus: Int? = null,
    requestId: String? = null,
    responseId: String? = null,
): OpenAiModerationBlockedException? {
    val reason = explicitModerationReason() ?: return null
    val diagnostic = toFailureDiagnostic()
    return OpenAiModerationBlockedException(
        reason = reason,
        httpStatus = httpStatus ?: diagnostic?.httpStatus,
        requestId = requestId ?: diagnostic?.requestId,
        responseId = responseId ?: diagnostic?.responseId,
        diagnostic = diagnostic,
    )
}

private fun ResponsesStreamEvent.responseIdentifier(): String? = when (this) {
    is ResponsesStreamEvent.Metadata -> responseId
    is ResponsesStreamEvent.Created -> response.id
    is ResponsesStreamEvent.InProgress -> response.id
    is ResponsesStreamEvent.Completed -> response.id
    is ResponsesStreamEvent.Failed -> response.id
    is ResponsesStreamEvent.Incomplete -> response.id
    else -> null
}

internal fun Throwable.isRetryableOpenAiException(
    retry: OpenAiClientRetryConfig,
): Boolean {
    val cause = openAiRootCause()
    if (cause is CancellationException) return false
    val status = openAiHttpStatus()
    if (status != null && status !in 200..299) {
        return HttpStatusCode.fromValue(status).isRetryableOpenAiStatus(retry)
    }
    return cause.isRetryableOpenAiTransportException(retry)
}

private fun Throwable.openAiHttpStatus(): Int? = openAiHttpResponse()?.status?.value

private fun Throwable.openAiHttpResponse(): HttpResponse? {
    var current: Throwable? = this
    var successfulResponse: HttpResponse? = null
    while (current != null) {
        val response = when (current) {
            is SSEClientException -> current.response
            is io.ktor.client.plugins.ResponseException -> current.response
            else -> null
        }
        if (response != null) {
            if (response.status.value !in 200..299) return response
            if (successfulResponse == null) successfulResponse = response
        }
        current = current.cause
    }
    return successfulResponse
}

internal suspend fun Throwable.hasHttpPolicyBlock(): Boolean =
    httpFailureDiagnostic()?.reason == OpenAiFailureReason.ContentPolicyViolation

private suspend fun Throwable.httpFailureDiagnostic(): OpenAiFailureDiagnostic? =
    openAiHttpResponse()?.httpFailureDiagnostic()

private suspend fun HttpResponse.httpFailureDiagnostic(): OpenAiFailureDiagnostic? =
    try {
        bodyAsText().httpFailureDiagnostic()?.copy(httpStatus = status.value)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

internal fun String.safeHttpFailureReason(): OpenAiFailureReason? {
    return httpFailureDiagnostic()?.reason
}

private fun String.httpFailureDiagnostic(): OpenAiFailureDiagnostic? {
    if (length > MaxOpenAiErrorBodyBytes || !hasSafeJsonDepth()) return null
    return try {
        val payload = Json.parseToJsonElement(this) as? JsonObject
        val error = (payload?.get("error") as? JsonObject) ?: payload
        val code = (error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val type = (error?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val message = (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val reason = if (code == "content_policy_violation" || type == "content_policy_violation") {
            OpenAiFailureReason.ContentPolicyViolation
        } else {
            safeFailureReason(code) ?: safeFailureReason(type)
        }
        OpenAiFailureDiagnostic(
            OpenAiFailureTermination.HttpError,
            reason = reason,
            errorCode = code,
            errorType = type,
            errorMessage = message,
        )
    } catch (_: Exception) {
        null
    }
}

private fun String.hasSafeJsonDepth(): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in this) {
        if (quoted) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MaxOpenAiErrorJsonDepth) return false
                '}', ']' -> depth--
            }
        }
    }
    return true
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
