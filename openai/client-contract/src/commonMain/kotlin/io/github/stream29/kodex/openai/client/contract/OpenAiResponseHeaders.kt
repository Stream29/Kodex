package io.github.stream29.kodex.openai.client.contract

/**
 * Safe response-header metadata exposed by the Responses transport.
 *
 * Raw headers are intentionally not exposed to AgentState.
 */
public data class OpenAiResponseHeaders(
    public val turnState: String?,
    public val requestId: String?,
)
