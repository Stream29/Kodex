package io.github.stream29.codex.lite.llmprovider

import kotlinx.coroutines.flow.Flow

public interface LlmProvider {
    public suspend fun listModels(): ModelsResponse
    public suspend fun createResponse(request: ResponsesApiRequest): Response
    public fun streamResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent>
    public suspend fun compactResponse(request: CompactionInput): CompactionResponse
}

/**
 * @property status Nullable because failures may occur before an HTTP response;
 * `null` means no HTTP status was available.
 * @property responseBody Nullable because failures may not include a response
 * body; `null` means no body was available.
 * @property code Nullable because backend errors may omit a code; `null` means
 * no structured error code was provided.
 * @property type Nullable because backend errors may omit a type; `null` means
 * no structured error type was provided.
 * @property requestId Nullable because responses may omit request id headers;
 * `null` means no request id was available.
 * @property cfRay Nullable because responses may omit Cloudflare ray headers;
 * `null` means no ray id was available.
 */
public class LlmProviderException(
    public val status: Int? = null,
    override val message: String,
    public val responseBody: String? = null,
    public val code: String? = null,
    public val type: String? = null,
    public val requestId: String? = null,
    public val cfRay: String? = null,
) : Exception(message)
