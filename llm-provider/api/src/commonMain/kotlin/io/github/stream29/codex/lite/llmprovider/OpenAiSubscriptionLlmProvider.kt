package io.github.stream29.codex.lite.llmprovider

import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.OpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClientConfig
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient as OpenAiClientContract
import kotlinx.coroutines.flow.Flow

public class OpenAiSubscriptionLlmProvider private constructor(
    private val client: OpenAiClientContract,
) : LlmProvider {
    public constructor(
        authProvider: OpenAiSubscriptionAuthSession,
        config: OpenAiClientConfig = OpenAiClientConfig(),
    ) : this(
        OpenAiClient(
            authProvider = authProvider,
            config = config,
        ),
    )

    override suspend fun listModels(): OpenAiResponseResult<ModelsResponse> =
        client.listModels()

    override suspend fun createResponse(request: ResponsesApiRequest): Flow<ResponsesStreamEvent> =
        client.createResponse(request)

    override fun close(): Unit {
        client.close()
    }
}
