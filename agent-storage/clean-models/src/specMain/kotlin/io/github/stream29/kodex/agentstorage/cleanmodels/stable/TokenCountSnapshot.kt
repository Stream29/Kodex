package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface TokenCountSnapshot : AgentCommit {
    public val totalTokens: Long

    @Serializable
    @SerialName("token_count_legacy")
    public data class Legacy(
        @SerialName("total_tokens")
        override val totalTokens: Long,
    ) : TokenCountSnapshot

    @Serializable
    @SerialName("token_count_initialization")
    public data class Initialization(
        @SerialName("total_tokens")
        override val totalTokens: Long = 0L,
    ) : TokenCountSnapshot

    @Serializable
    @SerialName("token_count_compaction")
    public data class Compaction(
        @SerialName("total_tokens")
        override val totalTokens: Long = 0L,
    ) : TokenCountSnapshot

    @Serializable
    @SerialName("token_count_response")
    public data class Response(
        @SerialName("total_tokens")
        override val totalTokens: Long,
        public val usage: TokenUsage? = null,
        public val diagnostics: TokenCountDiagnostics? = null,
    ) : TokenCountSnapshot
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
