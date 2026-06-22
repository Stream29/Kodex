package io.github.stream29.codex.lite.llmprovider

import kotlinx.coroutines.flow.Flow

public interface LlmProvider {
    public suspend fun listModels(): ModelsResponse
    public suspend fun createResponse(request: ResponsesApiRequest): Response
    public fun streamResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent>
    public suspend fun compactResponse(request: CompactionInput): CompactionResponse
}

public class LlmProviderException(
    public val status: Int? = null,
    override val message: String,
    public val responseBody: String? = null,
    public val code: String? = null,
    public val type: String? = null,
    public val requestId: String? = null,
    public val cfRay: String? = null,
) : Exception(message)
