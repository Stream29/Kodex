package io.github.stream29.codex.lite.llmprovider

import kotlinx.coroutines.flow.Flow

public interface LlmProvider {
    public suspend fun listModels(): LlmModels
    public suspend fun createResponse(request: LlmResponseRequest): LlmResponse
    public fun streamResponse(request: LlmResponseRequest): Flow<LlmResponseStreamEvent>
    public suspend fun compactResponse(request: LlmCompactionRequest): LlmCompactionResponse
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
