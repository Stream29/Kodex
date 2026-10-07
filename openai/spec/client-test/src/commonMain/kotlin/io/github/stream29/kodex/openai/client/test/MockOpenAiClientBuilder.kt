package io.github.stream29.kodex.openai.client.test

import io.github.stream29.kodex.openai.CodexAccountUsageResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeRequest
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetCreditsResponse
import io.github.stream29.kodex.openai.CodexTokenUsageProfile
import io.github.stream29.kodex.openai.ImageEditRequest
import io.github.stream29.kodex.openai.ImageGenerationRequest
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchRequest
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import kotlinx.coroutines.flow.Flow

/**
 * Test-only handler configuration for an [OpenAiClient] double.
 *
 * Each configured handler replaces that operation's previous handler. An
 * unconfigured operation fails when called instead of making a network
 * request. The full Responses handler initially delegates to the simple
 * Responses handler, allowing tests to configure either level explicitly.
 */
public interface MockOpenAiClientBuilder {
    public fun listModels(handler: suspend () -> OpenAiResponseResult<ModelsResponse>)

    public fun getCodexAccountUsage(handler: suspend () -> OpenAiResponseResult<CodexAccountUsageResponse>)

    public fun listCodexRateLimitResetCredits(
        handler: suspend () -> OpenAiResponseResult<CodexRateLimitResetCreditsResponse>,
    )

    public fun consumeCodexRateLimitResetCredit(
        handler: suspend (
            CodexRateLimitResetConsumeRequest,
            OpenAiSubscriptionAuthState,
        ) -> OpenAiResponseResult<CodexRateLimitResetConsumeResponse>,
    )

    public fun getCodexTokenUsageProfile(handler: suspend () -> OpenAiResponseResult<CodexTokenUsageProfile>)

    public fun createResponse(handler: suspend (ResponsesApiRequest) -> Flow<ResponsesStreamEvent>)

    public fun createResponse(
        handler: suspend (
            ResponsesApiRequest,
            suspend (OpenAiResponseHeaders) -> Unit,
        ) -> Flow<ResponsesStreamEvent>,
    )

    public fun createRemoteCompactionV2Response(
        handler: suspend (ResponsesApiRequest) -> RemoteCompactionV2Response,
    )

    public fun generateImage(handler: suspend (ImageGenerationRequest) -> OpenAiResponseResult<ImageResponse>)

    public fun editImage(handler: suspend (ImageEditRequest) -> OpenAiResponseResult<ImageResponse>)

    public fun search(handler: suspend (SearchRequest) -> OpenAiResponseResult<SearchResponse>)

    /** Builds an independent client double from the currently configured handlers. */
    public fun build(): OpenAiClient
}
