package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted LLM request metadata and usage snapshot, capturing token consumption,
 * latency, retry records, and request-level diagnostics.
 */
@Serializable
public sealed interface LlmRequestMeta : AgentCommit {
    public val totalTokens: Long

    /** Usage-only fallback when a provider response has no complete measurement. */
    @Serializable
    @SerialName("llm_request_meta_legacy")
    public data class Legacy(
        @SerialName("total_tokens")
        override val totalTokens: Long,
    ) : LlmRequestMeta

    @Serializable
    @SerialName("llm_request_meta_initialization")
    public data class Initialization(
        @SerialName("total_tokens")
        override val totalTokens: Long = 0L,
    ) : LlmRequestMeta

    @Serializable
    @SerialName("llm_request_meta_compaction")
    public data class Compaction(
        @SerialName("total_tokens")
        override val totalTokens: Long = 0L,
    ) : LlmRequestMeta

    @Serializable
    @SerialName("llm_request_meta_response")
    public data class Response(
        @SerialName("total_tokens")
        override val totalTokens: Long,
        @SerialName("elapsed_ns")
        public val elapsedNanoseconds: Long,
        @SerialName("output_tokens")
        public val outputTokens: Long,
        public val usage: TokenUsage? = null,
        public val diagnostics: TokenCountDiagnostics? = null,
        public val retries: Int = 0,
        @SerialName("latency_ms")
        public val latencyMs: Long? = null,
    ) : LlmRequestMeta {
        init {
            require(elapsedNanoseconds > 0) { "Response elapsed_ns must be positive." }
            require(outputTokens >= 0) { "Response output_tokens must be nonnegative." }
        }
    }
}

@Serializable
public data class TokenCountDiagnostics(
    @SerialName("turn_id")
    public val turnId: String? = null,
    @SerialName("window_id")
    public val windowId: String? = null,
    @SerialName("requested_model")
    public val requestedModel: String? = null,
    @SerialName("requested_reasoning_effort")
    public val requestedReasoningEffort: String? = null,
    @SerialName("requested_service_tier")
    public val requestedServiceTier: String? = null,
    @SerialName("response_id")
    public val responseId: String? = null,
    @SerialName("request_id")
    public val requestId: String? = null,
    public val model: String? = null,
    @SerialName("service_tier")
    public val serviceTier: String? = null,
    @SerialName("turn_state_sent")
    public val turnStateSent: Boolean? = null,
    @SerialName("turn_state_received")
    public val turnStateReceived: Boolean? = null,
    @SerialName("turn_state_adopted")
    public val turnStateAdopted: Boolean? = null,
)
