package io.github.stream29.codex.lite.openai.client.test

import io.github.stream29.codex.lite.openai.ImageEditRequest
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.SearchRequest
import io.github.stream29.codex.lite.openai.SearchResponse
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import kotlinx.coroutines.flow.Flow

public fun mockOpenAiClient(
    block: MockOpenAiClientBuilder.() -> Unit = {},
): OpenAiClient =
    MockOpenAiClientBuilder().apply(block).build()

public class MockOpenAiClientBuilder {
    private var listModelsHandler: suspend () -> OpenAiResponseResult<ModelsResponse> = { missingHandler("listModels") }
    private var createResponseHandler: suspend (ResponsesApiRequest) -> Flow<ResponsesStreamEvent> = {
        missingHandler("createResponse")
    }
    private var codexResponseHandler: suspend (ResponsesApiRequest, String?, String, String) -> Flow<ResponsesStreamEvent> = {
            request, _, _, _ ->
        createResponseHandler(request)
    }
    private var createRemoteCompactionV2ResponseHandler:
        suspend (ResponsesApiRequest, String?, String, String) -> RemoteCompactionV2Response = { _, _, _, _ ->
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

    public fun listModels(handler: suspend () -> OpenAiResponseResult<ModelsResponse>): Unit {
        listModelsHandler = handler
    }

    public fun createResponse(handler: suspend (ResponsesApiRequest) -> Flow<ResponsesStreamEvent>): Unit {
        createResponseHandler = handler
    }

    public fun createResponse(
        handler: suspend (ResponsesApiRequest, String?, String, String) -> Flow<ResponsesStreamEvent>,
    ): Unit {
        codexResponseHandler = handler
    }

    public fun createRemoteCompactionV2Response(
        handler: suspend (ResponsesApiRequest, String?, String, String) -> RemoteCompactionV2Response,
    ): Unit {
        createRemoteCompactionV2ResponseHandler = handler
    }

    public fun generateImage(handler: suspend (ImageGenerationRequest) -> OpenAiResponseResult<ImageResponse>): Unit {
        generateImageHandler = handler
    }

    public fun editImage(handler: suspend (ImageEditRequest) -> OpenAiResponseResult<ImageResponse>): Unit {
        editImageHandler = handler
    }

    public fun search(handler: suspend (SearchRequest) -> OpenAiResponseResult<SearchResponse>): Unit {
        searchHandler = handler
    }

    public fun build(): OpenAiClient =
        MockOpenAiClient(
            listModelsHandler = listModelsHandler,
            createResponseHandler = createResponseHandler,
            codexResponseHandler = codexResponseHandler,
            createRemoteCompactionV2ResponseHandler = createRemoteCompactionV2ResponseHandler,
            generateImageHandler = generateImageHandler,
            editImageHandler = editImageHandler,
            searchHandler = searchHandler,
        )
}

private class MockOpenAiClient(
    private val listModelsHandler: suspend () -> OpenAiResponseResult<ModelsResponse>,
    private val createResponseHandler: suspend (ResponsesApiRequest) -> Flow<ResponsesStreamEvent>,
    private val codexResponseHandler: suspend (ResponsesApiRequest, String?, String, String) -> Flow<ResponsesStreamEvent>,
    private val createRemoteCompactionV2ResponseHandler:
        suspend (ResponsesApiRequest, String?, String, String) -> RemoteCompactionV2Response,
    private val generateImageHandler: suspend (ImageGenerationRequest) -> OpenAiResponseResult<ImageResponse>,
    private val editImageHandler: suspend (ImageEditRequest) -> OpenAiResponseResult<ImageResponse>,
    private val searchHandler: suspend (SearchRequest) -> OpenAiResponseResult<SearchResponse>,
) : OpenAiClient {
    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> =
        listModelsHandler()

    override suspend fun createResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent> =
        createResponseHandler(request)

    override suspend fun createResponse(
        request: ResponsesApiRequest,
        installationId: String?,
        turnMetadata: String,
        windowId: String,
    ): Flow<ResponsesStreamEvent> =
        codexResponseHandler(request, installationId, turnMetadata, windowId)

    override suspend fun createRemoteCompactionV2Response(
        request: ResponsesApiRequest,
        installationId: String?,
        turnMetadata: String,
        windowId: String,
    ): RemoteCompactionV2Response =
        createRemoteCompactionV2ResponseHandler(request, installationId, turnMetadata, windowId)

    override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse> =
        generateImageHandler(request)

    override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse> =
        editImageHandler(request)

    override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse> =
        searchHandler(request)
}

private fun <T> missingHandler(name: String): T =
    throw IllegalStateException("MockOpenAiClient handler is not configured for `$name`.")
