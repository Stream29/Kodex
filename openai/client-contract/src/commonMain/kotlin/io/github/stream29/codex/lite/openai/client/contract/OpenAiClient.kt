package io.github.stream29.codex.lite.openai.client.contract

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
import kotlinx.coroutines.flow.Flow

public interface OpenAiClient : AutoCloseable {
    public suspend fun listModels(): OpenAiResponseResult<ModelsResponse>

    public suspend fun createResponse(
        request: ResponsesApiRequest,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Flow<ResponsesStreamEvent>

    public suspend fun createRemoteCompactionV2Response(request: RemoteCompactionV2Request): RemoteCompactionV2Response

    public suspend fun compactResponse(request: CompactionInput): OpenAiResponseResult<CompactionResponse>

    public suspend fun generateImage(request: ImageGenerationRequest): OpenAiResponseResult<ImageResponse>

    public suspend fun editImage(request: ImageEditRequest): OpenAiResponseResult<ImageResponse>

    public suspend fun search(request: SearchRequest): OpenAiResponseResult<SearchResponse>

    override fun close(): Unit = Unit
}
