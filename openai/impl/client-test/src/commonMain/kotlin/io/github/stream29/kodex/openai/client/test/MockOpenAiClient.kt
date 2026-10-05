package io.github.stream29.kodex.openai.client.test

import io.github.stream29.kodex.openai.CodexAccountUsageResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeRequest
import io.github.stream29.kodex.openai.CodexRateLimitResetConsumeResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetCreditsResponse
import io.github.stream29.kodex.openai.CodexTokenUsageProfile
import io.github.stream29.kodex.openai.CodexResponsesClientMetadata
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
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchRequest
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.TextControls
import io.github.stream29.kodex.openai.ToolChoice
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import kotlinx.coroutines.flow.Flow

public fun mockOpenAiClient(
    block: MockOpenAiClientBuilder.() -> Unit = {},
): OpenAiClient =
    MockOpenAiClientBuilderImpl().apply(block).build()

/** Creates a test-only OpenAI client handler builder. */
public fun MockOpenAiClientBuilder(): MockOpenAiClientBuilder =
    MockOpenAiClientBuilderImpl()

internal class MockOpenAiClientBuilderImpl : MockOpenAiClientBuilder {
    private var listModelsHandler: suspend () -> OpenAiResponseResult<ModelsResponse> = { missingHandler("listModels") }
    private var getCodexAccountUsageHandler: suspend () -> OpenAiResponseResult<CodexAccountUsageResponse> = {
        missingHandler("getCodexAccountUsage")
    }
    private var listCodexRateLimitResetCreditsHandler:
        suspend () -> OpenAiResponseResult<CodexRateLimitResetCreditsResponse> = {
            missingHandler("listCodexRateLimitResetCredits")
        }
    private var consumeCodexRateLimitResetCreditHandler:
        suspend (
            CodexRateLimitResetConsumeRequest,
            OpenAiSubscriptionAuthState,
        ) -> OpenAiResponseResult<CodexRateLimitResetConsumeResponse> = { _, _ ->
            missingHandler("consumeCodexRateLimitResetCredit")
        }
    private var getCodexTokenUsageProfileHandler: suspend () -> OpenAiResponseResult<CodexTokenUsageProfile> = {
        missingHandler("getCodexTokenUsageProfile")
    }
    private var createResponseHandler: suspend (ResponsesApiRequest) -> Flow<ResponsesStreamEvent> = {
        missingHandler("createResponse")
    }
    private var codexResponseHandler: (suspend (
        ResponsesApiRequest,
        suspend (OpenAiResponseHeaders) -> Unit,
    ) -> Flow<ResponsesStreamEvent>)? = null
    private var createRemoteCompactionV2ResponseHandler:
        suspend (ResponsesApiRequest) -> RemoteCompactionV2Response = { _ ->
            missingHandler("createRemoteCompactionV2Response")
        }
    private var generateImageHandler: suspend (ImageGenerationRequest) -> OpenAiResponseResult<ImageResponse> = {
        missingHandler("generateImage")
    }
    private var editImageHandler: suspend (ImageEditRequest) -> OpenAiResponseResult<ImageResponse> = {
        missingHandler("editImage")
    }
    private var searchHandler: suspend (SearchRequest) -> OpenAiResponseResult<SearchResponse> = {
        missingHandler("search")
    }

    override fun listModels(handler: suspend () -> OpenAiResponseResult<ModelsResponse>): Unit {
        listModelsHandler = handler
    }

    override fun getCodexAccountUsage(
        handler: suspend () -> OpenAiResponseResult<CodexAccountUsageResponse>,
    ): Unit {
        getCodexAccountUsageHandler = handler
    }

    override fun listCodexRateLimitResetCredits(
        handler: suspend () -> OpenAiResponseResult<CodexRateLimitResetCreditsResponse>,
    ): Unit {
        listCodexRateLimitResetCreditsHandler = handler
    }

    override fun consumeCodexRateLimitResetCredit(
        handler:
            suspend (
                CodexRateLimitResetConsumeRequest,
                OpenAiSubscriptionAuthState,
            ) ->
                OpenAiResponseResult<CodexRateLimitResetConsumeResponse>,
    ): Unit {
        consumeCodexRateLimitResetCreditHandler = handler
    }

    override fun getCodexTokenUsageProfile(
        handler: suspend () -> OpenAiResponseResult<CodexTokenUsageProfile>,
    ): Unit {
        getCodexTokenUsageProfileHandler = handler
    }

    override fun createResponse(handler: suspend (ResponsesApiRequest) -> Flow<ResponsesStreamEvent>): Unit {
        createResponseHandler = handler
    }

    override fun createResponse(
        handler: suspend (
            ResponsesApiRequest,
            suspend (OpenAiResponseHeaders) -> Unit,
        ) -> Flow<ResponsesStreamEvent>,
    ): Unit {
        codexResponseHandler = { request, onResponseHeaders ->
            handler(request, onResponseHeaders)
        }
    }

    override fun createRemoteCompactionV2Response(
        handler: suspend (ResponsesApiRequest) -> RemoteCompactionV2Response,
    ): Unit {
        createRemoteCompactionV2ResponseHandler = handler
    }

    override fun generateImage(handler: suspend (ImageGenerationRequest) -> OpenAiResponseResult<ImageResponse>): Unit {
        generateImageHandler = handler
    }

    override fun editImage(handler: suspend (ImageEditRequest) -> OpenAiResponseResult<ImageResponse>): Unit {
        editImageHandler = handler
    }

    override fun search(handler: suspend (SearchRequest) -> OpenAiResponseResult<SearchResponse>): Unit {
        searchHandler = handler
    }

    override fun build(): OpenAiClient {
        val simpleResponseHandler = createResponseHandler
        val responseHandler: suspend (
            ResponsesApiRequest,
            suspend (OpenAiResponseHeaders) -> Unit,
        ) -> Flow<ResponsesStreamEvent> = codexResponseHandler ?: { request, _ ->
            simpleResponseHandler(request)
        }
        return MockOpenAiClient(
            listModelsHandler = listModelsHandler,
            getCodexAccountUsageHandler = getCodexAccountUsageHandler,
            listCodexRateLimitResetCreditsHandler = listCodexRateLimitResetCreditsHandler,
            consumeCodexRateLimitResetCreditHandler = consumeCodexRateLimitResetCreditHandler,
            getCodexTokenUsageProfileHandler = getCodexTokenUsageProfileHandler,
            codexResponseHandler = responseHandler,
            createRemoteCompactionV2ResponseHandler = createRemoteCompactionV2ResponseHandler,
            generateImageHandler = generateImageHandler,
            editImageHandler = editImageHandler,
            searchHandler = searchHandler,
        )
    }
}

private class MockOpenAiClient(
    private val listModelsHandler: suspend () -> OpenAiResponseResult<ModelsResponse>,
    private val getCodexAccountUsageHandler: suspend () -> OpenAiResponseResult<CodexAccountUsageResponse>,
    private val listCodexRateLimitResetCreditsHandler:
        suspend () -> OpenAiResponseResult<CodexRateLimitResetCreditsResponse>,
    private val consumeCodexRateLimitResetCreditHandler:
        suspend (
            CodexRateLimitResetConsumeRequest,
            OpenAiSubscriptionAuthState,
        ) -> OpenAiResponseResult<CodexRateLimitResetConsumeResponse>,
    private val getCodexTokenUsageProfileHandler: suspend () -> OpenAiResponseResult<CodexTokenUsageProfile>,
    private val codexResponseHandler:
        suspend (
            ResponsesApiRequest,
            suspend (OpenAiResponseHeaders) -> Unit,
        ) -> Flow<ResponsesStreamEvent>,
    private val createRemoteCompactionV2ResponseHandler:
        suspend (ResponsesApiRequest) -> RemoteCompactionV2Response,
    private val generateImageHandler: suspend (ImageGenerationRequest) -> OpenAiResponseResult<ImageResponse>,
    private val editImageHandler: suspend (ImageEditRequest) -> OpenAiResponseResult<ImageResponse>,
    private val searchHandler: suspend (SearchRequest) -> OpenAiResponseResult<SearchResponse>,
) : OpenAiClient {
    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> =
        listModelsHandler()

    override suspend fun getCodexAccountUsage(): OpenAiResponseResult<CodexAccountUsageResponse> =
        getCodexAccountUsageHandler()

    override suspend fun listCodexRateLimitResetCredits():
        OpenAiResponseResult<CodexRateLimitResetCreditsResponse> =
        listCodexRateLimitResetCreditsHandler()

    override suspend fun consumeCodexRateLimitResetCredit(
        request: CodexRateLimitResetConsumeRequest,
        expectedAccount: OpenAiSubscriptionAuthState,
    ): OpenAiResponseResult<CodexRateLimitResetConsumeResponse> =
        consumeCodexRateLimitResetCreditHandler(request, expectedAccount)

    override suspend fun getCodexTokenUsageProfile(): OpenAiResponseResult<CodexTokenUsageProfile> =
        getCodexTokenUsageProfileHandler()

    override suspend fun createResponse(
        model: OpenAiModelId,
        input: List<io.github.stream29.kodex.openai.ResponseItem>,
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
    ): Flow<ResponsesStreamEvent> =
        codexResponseHandler(
            ResponsesApiRequest(
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
                clientMetadata = mockCodexClientMetadata(
                    installationId = installationId,
                    sessionId = sessionId,
                    threadId = threadId,
                    turnId = turnId,
                    windowId = windowId,
                    turnState = turnState,
                ),
            ),
            onResponseHeaders,
        )

    override suspend fun createRemoteCompactionV2Response(
        request: ResponsesApiRequest,
    ): RemoteCompactionV2Response =
        createRemoteCompactionV2ResponseHandler(request)

    override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse> =
        generateImageHandler(request)

    override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse> =
        editImageHandler(request)

    override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse> =
        searchHandler(request)
}

private fun <T> missingHandler(name: String): T =
    throw IllegalStateException("MockOpenAiClient handler is not configured for `$name`.")

private fun mockCodexClientMetadata(
    installationId: String?,
    sessionId: String?,
    threadId: String?,
    turnId: String?,
    windowId: String?,
    turnState: String?,
): CodexResponsesClientMetadata? {
    if (
        installationId == null &&
        sessionId == null &&
        threadId == null &&
        turnId == null &&
        windowId == null &&
        turnState == null
    ) {
        return null
    }
    require(threadId != null) { "threadId is required when Codex request metadata is supplied." }
    require(windowId != null) { "windowId is required when Codex request metadata is supplied." }
    return CodexResponsesClientMetadata(
        installationId = installationId,
        sessionId = sessionId,
        threadId = threadId,
        turnId = turnId,
        windowId = windowId,
        turnMetadata = "mock-turn-metadata",
    )
}
