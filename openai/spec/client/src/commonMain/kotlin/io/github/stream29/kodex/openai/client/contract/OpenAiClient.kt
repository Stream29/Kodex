package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.CodexAccountUsageResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeRequest
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetCreditsResponse
import io.github.stream29.kodex.openai.CodexTokenUsageProfile
import io.github.stream29.kodex.openai.ImageEditRequest
import io.github.stream29.kodex.openai.ImageGenerationRequest
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ResponseInclude
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.SearchRequest
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.TextControls
import io.github.stream29.kodex.openai.ToolChoice
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.client.OpenAiRemoteCompactionV2ProtocolException
import io.github.stream29.kodex.openai.client.OpenAiRemoteCompactionV2StreamFailureException
import io.github.stream29.kodex.openai.client.OpenAiRemoteCompactionV2StreamIncompleteException
import kotlinx.coroutines.flow.Flow

/**
 * Bearer-authenticated OpenAI transport for one currently selected account.
 *
 * Every request observes the authentication store at submission time. JSON
 * operations return a structured [OpenAiResponseResult] for provider error
 * responses; authentication, transport, decoding, and cancellation failures
 * propagate instead of becoming a successful payload. Responses operations
 * expose protocol events as a [Flow], not a synthesized response object.
 */
public interface OpenAiClient : AutoCloseable {
    /**
     * Lists available model metadata from the selected account.
     *
     * @throws IllegalStateException if authentication is unavailable.
     */
    public suspend fun listModels(): OpenAiResponseResult<ModelsResponse>

    /**
     * Reads rate-limit usage for the selected Codex account.
     *
     * @throws IllegalStateException if authentication is unavailable.
     */
    public suspend fun getCodexAccountUsage(): OpenAiResponseResult<CodexAccountUsageResponse>

    /**
     * Lists the selected account's rate-limit reset credits.
     *
     * @throws IllegalStateException if authentication is unavailable.
     */
    public suspend fun listCodexRateLimitResetCredits():
        OpenAiResponseResult<CodexRateLimitResetCreditsResponse>

    /**
     * Consumes one reset for [expectedAccount], failing before submission if
     * authentication changed.
     *
     * @throws IllegalStateException if authentication is unavailable or
     * differs from [expectedAccount] before the request starts.
     */
    public suspend fun consumeCodexRateLimitResetCredit(
        request: CodexRateLimitResetConsumeRequest,
        expectedAccount: OpenAiSubscriptionAuthState,
    ): OpenAiResponseResult<CodexRateLimitResetConsumeResponse>

    /**
     * Reads token activity for the selected Codex account.
     *
     * @throws IllegalStateException if authentication is unavailable.
     */
    public suspend fun getCodexTokenUsageProfile(): OpenAiResponseResult<CodexTokenUsageProfile>

    /**
     * Sends one OpenAI `/responses` operation and yields its protocol events
     * in wire order.
     *
     * [model] and [input] are the only required operation values. The
     * remaining arguments are semantic controls with provider-compatible
     * defaults; callers do not need to construct or understand
     * [ResponsesApiRequest]. The implementation is responsible for projecting
     * these values into the JSON body and the transport headers.
     *
     * [promptCacheKey] controls provider prompt-cache affinity. When it is
     * `null` for a Codex turn, the implementation may use [threadId] as the
     * cache key. [installationId], [sessionId], [threadId], [turnId], and
     * [windowId] describe optional Codex request identity and are projected
     * into the request's `client_metadata` body and corresponding headers.
     * [threadId] and [windowId] must be supplied together whenever any Codex
     * identity field or [turnState] is supplied.
     *
     * [turnState] is transport state only. It is sent as the Codex turn-state
     * request header and is not part of the OpenAI `/responses` JSON body.
     * [onResponseHeaders] is invoked once, after the response headers have
     * been received and before the first body event is delivered. The
     * callback runs in the collection context and must not be used to mutate
     * the request.
     *
     * A retryable stream failure may end the flow without a terminal event;
     * consumers must not infer success merely from flow completion.
     *
     * @param model OpenAI model identifier.
     * @param input Ordered Responses input items.
     * @param instructions Optional model instructions.
     * @param store Whether OpenAI may store the response.
     * @param previousResponseId Optional response id to continue.
     * @param tools Tools exposed to the model.
     * @param toolChoice Tool-selection policy.
     * @param parallelToolCalls Whether parallel tool calls are allowed.
     * @param reasoning Reasoning effort and summary controls.
     * @param include Optional response expansions.
     * @param serviceTier Requested service tier.
     * @param promptCacheKey Optional prompt-cache affinity key.
     * @param text Text output controls.
     * @param installationId Optional Codex installation identity.
     * @param sessionId Optional Codex session identity.
     * @param threadId Optional Codex thread identity.
     * @param turnId Optional Codex turn identity.
     * @param windowId Optional Codex context-window identity.
     * @param turnState Optional server-issued Codex turn state for continuing
     * a turn.
     * @param onResponseHeaders Observer invoked before stream body events.
     *
     * @throws IllegalStateException if authentication is unavailable.
     * @throws IllegalArgumentException if only part of the Codex request
     * identity is supplied; `threadId` and `windowId` are required together
     * whenever Codex metadata or `turnState` is used.
     */
    public suspend fun createResponse(
        model: OpenAiModelId,
        input: List<ResponseItem>,
        instructions: String = "",
        store: Boolean = false,
        previousResponseId: String? = null,
        tools: List<ToolSpec> = emptyList(),
        toolChoice: ToolChoice = ToolChoice.Auto,
        parallelToolCalls: Boolean = false,
        reasoning: Reasoning = Reasoning(),
        include: Set<ResponseInclude> = emptySet(),
        serviceTier: ServiceTier = ServiceTier.Default,
        promptCacheKey: String? = null,
        text: TextControls = TextControls(),
        installationId: String? = null,
        sessionId: String? = null,
        threadId: String? = null,
        turnId: String? = null,
        windowId: String? = null,
        turnState: String? = null,
        onResponseHeaders: suspend (OpenAiResponseHeaders) -> Unit = {},
    ): Flow<ResponsesStreamEvent>

    /**
     * Runs a remote compaction request and returns its completed output.
     * Unlike ordinary Responses, this operation collects the stream within
     * the call and does not return a partial result on protocol failure.
     * Request-specific Codex headers for this operation are derived
     * exclusively from [request.clientMetadata]; the remote-compaction beta
     * header remains owned by the client implementation.
     *
     * @throws IllegalStateException if authentication is unavailable.
     * @throws OpenAiRemoteCompactionV2ProtocolException if the stream contains
     * an invalid compaction protocol event or output.
     * @throws OpenAiRemoteCompactionV2StreamIncompleteException if the stream
     * ends without compaction output.
     * @throws OpenAiRemoteCompactionV2StreamFailureException if the stream
     * reports a retryable failure.
     */
    public suspend fun createRemoteCompactionV2Response(
        request: ResponsesApiRequest,
    ): RemoteCompactionV2Response

    /**
     * Generates an image and returns its structured provider result.
     *
     * @throws IllegalStateException if authentication is unavailable.
     */
    public suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse>

    /**
     * Edits an image and returns its structured provider result.
     *
     * @throws IllegalStateException if authentication is unavailable.
     */
    public suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse>

    /**
     * Searches using the selected account and returns its structured result.
     *
     * @throws IllegalStateException if authentication is unavailable.
     */
    public suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse>

    /** Releases transport resources owned by this client, if any. */
    override fun close(): Unit = Unit
}
