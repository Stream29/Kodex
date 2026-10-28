package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.OpenAiFailureDiagnostic

/**
 * Safe response-header metadata exposed by the Responses transport.
 *
 * Raw headers are intentionally not exposed to AgentState.
 *
 * @property turnState Nullable server-provided state to send on a later
 * request in the same turn; `null` means no state was provided.
 * @property requestId Nullable request identifier for diagnostics.
 * @property failure Safe failure metadata when a retryable stream transport
 * terminates; in that case no response headers or turn state are reported.
 * @property httpStatus Status of the accepted SSE response, if observed.
 */
public data class OpenAiResponseHeaders(
    public val turnState: String?,
    public val requestId: String?,
    public val failure: OpenAiFailureDiagnostic? = null,
    public val httpStatus: Int? = null,
)
