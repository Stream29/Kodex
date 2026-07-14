package io.github.stream29.codex.lite.llmprovider

import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow

public interface LlmProvider : AutoCloseable {
    public suspend fun listModels(): OpenAiResponseResult<ModelsResponse>
    public suspend fun createResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent>
    override fun close(): Unit = Unit
}
