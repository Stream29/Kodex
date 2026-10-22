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
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponseInclude
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchRequest
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.TextControls
import io.github.stream29.kodex.openai.ToolChoice
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as OpenAiClientContract
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import kotlinx.coroutines.flow.Flow

public expect class OpenAiClient(
    authStore: OpenAiAuthStore,
    config: OpenAiClientConfig = OpenAiClientConfig(),
) : OpenAiClientContract {
    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse>
    override suspend fun getCodexAccountUsage(): OpenAiResponseResult<CodexAccountUsageResponse>
    override suspend fun listCodexRateLimitResetCredits(): OpenAiResponseResult<CodexRateLimitResetCreditsResponse>
    override suspend fun consumeCodexRateLimitResetCredit(
        request: CodexRateLimitResetConsumeRequest,
        expectedAccount: OpenAiSubscriptionAuthState,
    ): OpenAiResponseResult<CodexRateLimitResetConsumeResponse>
    override suspend fun getCodexTokenUsageProfile(): OpenAiResponseResult<CodexTokenUsageProfile>
    override suspend fun createResponse(
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
        turnState: String?,
        onResponseHeaders: suspend (OpenAiResponseHeaders) -> Unit,
    ): Flow<ResponsesStreamEvent>
    override suspend fun createRemoteCompactionV2Response(request: ResponsesApiRequest): RemoteCompactionV2Response
    override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse>
    override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse>
    override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse>
    override fun close()
}
