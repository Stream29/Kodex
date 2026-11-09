@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.TokenUsage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persisted LLM request metadata and usage snapshot, capturing token consumption,
 * latency, retry records, and request-level diagnostics.
 */
@Serializable
public sealed interface LlmRequestMeta : AgentCommit {
    public val totalTokens: Long

    @Serializable
    @SerialName("llm_request_meta_legacy")
    public data class Legacy(
        @SerialName("total_tokens")
        override val totalTokens: Long,
    ) : LlmRequestMeta, TokenCountSnapshot

    @Serializable
    @SerialName("llm_request_meta_initialization")
    public data class Initialization(
        @SerialName("total_tokens")
        override val totalTokens: Long = 0L,
    ) : LlmRequestMeta, TokenCountSnapshot

    @Serializable
    @SerialName("llm_request_meta_compaction")
    public data class Compaction(
        @SerialName("total_tokens")
        override val totalTokens: Long = 0L,
    ) : LlmRequestMeta, TokenCountSnapshot

    @Serializable
    @SerialName("llm_request_meta_response")
    public data class Response(
        @SerialName("total_tokens")
        override val totalTokens: Long,
        public val usage: TokenUsage? = null,
        public val diagnostics: TokenCountDiagnostics? = null,
        public val retries: Int = 0,
        @SerialName("latency_ms")
        public val latencyMs: Long? = null,
    ) : LlmRequestMeta, TokenCountSnapshot
}

@Serializable(with = TokenCountSnapshotSerializer::class)
public sealed interface TokenCountSnapshot : AgentCommit {
    public val totalTokens: Long

    public typealias Legacy = LlmRequestMeta.Legacy
    public typealias Initialization = LlmRequestMeta.Initialization
    public typealias Compaction = LlmRequestMeta.Compaction
    public typealias Response = LlmRequestMeta.Response
}

public object TokenCountSnapshotSerializer : KSerializer<TokenCountSnapshot> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TokenCountSnapshot")

    override fun serialize(encoder: Encoder, value: TokenCountSnapshot) {
        encoder.encodeSerializableValue(LlmRequestMeta.serializer(), value as LlmRequestMeta)
    }

    override fun deserialize(decoder: Decoder): TokenCountSnapshot {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(LlmRequestMeta.serializer()) as TokenCountSnapshot
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? kotlinx.serialization.json.JsonObject
            ?: return jsonDecoder.json.decodeFromJsonElement(LlmRequestMeta.serializer(), element) as TokenCountSnapshot
        val type = obj["type"]?.jsonPrimitive?.content
        val stripped = kotlinx.serialization.json.JsonObject(obj.filterKeys { it != "type" })
        val json = jsonDecoder.json
        return when (type) {
            "token_count_legacy", "llm_request_meta_legacy" ->
                json.decodeFromJsonElement(LlmRequestMeta.Legacy.serializer(), stripped)
            "token_count_initialization", "llm_request_meta_initialization" ->
                json.decodeFromJsonElement(LlmRequestMeta.Initialization.serializer(), stripped)
            "token_count_compaction", "llm_request_meta_compaction" ->
                json.decodeFromJsonElement(LlmRequestMeta.Compaction.serializer(), stripped)
            "token_count_response", "llm_request_meta_response" ->
                json.decodeFromJsonElement(LlmRequestMeta.Response.serializer(), stripped)
            else -> json.decodeFromJsonElement(LlmRequestMeta.serializer(), element) as TokenCountSnapshot
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
