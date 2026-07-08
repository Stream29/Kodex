package io.github.stream29.codex.lite.openai.client.test

import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.CompactionResponse
import io.github.stream29.codex.lite.openai.ImageEditRequest
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
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
    private var createResponseHandler: suspend (ResponsesApiRequest, Map<String, String>) -> Flow<ResponsesStreamEvent> = { _, _ ->
        missingHandler("createResponse")
    }
    private var createRemoteCompactionV2ResponseHandler: suspend (RemoteCompactionV2Request) -> RemoteCompactionV2Response = {
        missingHandler("createRemoteCompactionV2Response")
    }
    private var compactResponseHandler: suspend (CompactionInput) -> OpenAiResponseResult<CompactionResponse> = {
        missingHandler("compactResponse")
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
        createResponseHandler = { request, _ -> handler(request) }
    }

    public fun createResponseWithHeaders(
        handler: suspend (ResponsesApiRequest, Map<String, String>) -> Flow<ResponsesStreamEvent>,
    ): Unit {
        createResponseHandler = handler
    }

    public fun createRemoteCompactionV2Response(
        handler: suspend (RemoteCompactionV2Request) -> RemoteCompactionV2Response,
    ): Unit {
        createRemoteCompactionV2ResponseHandler = handler
    }

    public fun compactResponse(handler: suspend (CompactionInput) -> OpenAiResponseResult<CompactionResponse>): Unit {
        compactResponseHandler = handler
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
            createRemoteCompactionV2ResponseHandler = createRemoteCompactionV2ResponseHandler,
            compactResponseHandler = compactResponseHandler,
            generateImageHandler = generateImageHandler,
            editImageHandler = editImageHandler,
            searchHandler = searchHandler,
        )
}

private class MockOpenAiClient(
    private val listModelsHandler: suspend () -> OpenAiResponseResult<ModelsResponse>,
    private val createResponseHandler: suspend (ResponsesApiRequest, Map<String, String>) -> Flow<ResponsesStreamEvent>,
    private val createRemoteCompactionV2ResponseHandler: suspend (RemoteCompactionV2Request) -> RemoteCompactionV2Response,
    private val compactResponseHandler: suspend (CompactionInput) -> OpenAiResponseResult<CompactionResponse>,
    private val generateImageHandler: suspend (ImageGenerationRequest) -> OpenAiResponseResult<ImageResponse>,
    private val editImageHandler: suspend (ImageEditRequest) -> OpenAiResponseResult<ImageResponse>,
    private val searchHandler: suspend (SearchRequest) -> OpenAiResponseResult<SearchResponse>,
) : OpenAiClient {
    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> =
        listModelsHandler()

    override suspend fun createResponse(
        request: ResponsesApiRequest,
        extraHeaders: Map<String, String>,
    ): Flow<ResponsesStreamEvent> =
        createResponseHandler(request, extraHeaders)

    override suspend fun createRemoteCompactionV2Response(
        request: RemoteCompactionV2Request,
    ): RemoteCompactionV2Response =
        createRemoteCompactionV2ResponseHandler(request)

    override suspend fun compactResponse(request: CompactionInput): OpenAiResponseResult<CompactionResponse> =
        compactResponseHandler(request)

    override suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse> =
        generateImageHandler(request)

    override suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse> =
        editImageHandler(request)

    override suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse> =
        searchHandler(request)
}

private fun <T> missingHandler(name: String): T =
    throw IllegalStateException("MockOpenAiClient handler is not configured for `$name`.")
