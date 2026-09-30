package io.github.stream29.kodex.openai.client.contract

/**
 * Safe response-header metadata exposed by the Responses transport.
 *
 * Raw headers are intentionally not exposed to AgentState.
 *
 * @property turnState Nullable server-provided state to send on a later
 * request in the same turn; `null` means no state was provided.
 * @property requestId Nullable request identifier for diagnostics.
 */
public data class OpenAiResponseHeaders(
    public val turnState: String?,
    public val requestId: String?,
)
