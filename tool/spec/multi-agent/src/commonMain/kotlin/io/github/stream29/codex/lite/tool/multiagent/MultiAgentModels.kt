package io.github.stream29.codex.lite.tool.multiagent

import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ServiceTier
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Input for `spawn_agent`.
 *
 * @property forkTurns Nullable because callers may accept the default full-history fork;
 * `null` means `all`.
 * @property model Nullable because a child normally inherits its parent model; `null` means inherit.
 * @property reasoningEffort Nullable because a child normally inherits its parent effort; `null` means inherit.
 * @property serviceTier Nullable because a child normally inherits its parent tier; `null` means inherit.
 */
@Serializable
public data class SpawnAgentArgs(
    @SerialName("task_name")
    public val taskName: String,
    public val message: String,
    @SerialName("fork_turns")
    public val forkTurns: String? = null,
    public val model: OpenAiModelId? = null,
    @SerialName("reasoning_effort")
    public val reasoningEffort: ReasoningEffort? = null,
    @SerialName("service_tier")
    public val serviceTier: ServiceTier? = null,
)

/**
 * Successful `spawn_agent` result.
 *
 * @property nickname Nullable because Codex Lite does not require a separate nickname;
 * `null` means the canonical [taskName] is the only display identity.
 */
@Serializable
public data class SpawnAgentResult(
    @SerialName("task_name")
    public val taskName: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val nickname: String? = null,
)

@Serializable
public data class SendMessageArgs(
    public val target: String,
    public val message: String,
)

@Serializable
public data class FollowupTaskArgs(
    public val target: String,
    public val message: String,
)

/**
 * Input for `wait_agent`.
 *
 * @property timeoutMs Nullable because callers may accept the coordinator default;
 * `null` means use the configured default timeout.
 */
@Serializable
public data class WaitAgentArgs(
    @SerialName("timeout_ms")
    public val timeoutMs: Long? = null,
)

@Serializable
public data class WaitAgentResult(
    public val message: String,
    @SerialName("timed_out")
    public val timedOut: Boolean,
)

@Serializable
public data class InterruptAgentArgs(
    public val target: String,
)

@Serializable
public data class InterruptAgentResult(
    @SerialName("previous_status")
    public val previousStatus: MultiAgentStatus,
)

/**
 * Input for `list_agents`.
 *
 * @property pathPrefix Nullable because filtering is optional; `null` means list the complete tree.
 */
@Serializable
public data class ListAgentsArgs(
    @SerialName("path_prefix")
    public val pathPrefix: String? = null,
)

@Serializable
public data class ListAgentsResult(
    public val agents: List<ListedAgent>,
)

/**
 * One model-facing live-Agent projection.
 *
 * @property lastTaskMessage Nullable because a restored or never-instructed Agent may have no
 * known task message; `null` means no message preview is available.
 */
@Serializable
public data class ListedAgent(
    @SerialName("agent_name")
    public val agentName: String,
    @SerialName("agent_status")
    public val agentStatus: MultiAgentStatus,
    @SerialName("last_task_message")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val lastTaskMessage: String? = null,
)

/** Rust-compatible model-facing Agent lifecycle status. */
@Serializable(with = MultiAgentStatusSerializer::class)
public sealed interface MultiAgentStatus {
    public data object PendingInit : MultiAgentStatus
    public data object Running : MultiAgentStatus
    public data object Interrupted : MultiAgentStatus

    /**
     * @property message Nullable because a completed turn may contain no final assistant text;
     * `null` means no final text was emitted.
     */
    public data class Completed(public val message: String?) : MultiAgentStatus

    public data class Errored(public val message: String) : MultiAgentStatus
    public data object Shutdown : MultiAgentStatus
    public data object NotFound : MultiAgentStatus
}

public object MultiAgentStatusSerializer : KSerializer<MultiAgentStatus> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: MultiAgentStatus) {
        require(encoder is JsonEncoder) { "MultiAgentStatus can only be encoded as JSON." }
        val element = when (value) {
            MultiAgentStatus.PendingInit -> JsonPrimitive("pending_init")
            MultiAgentStatus.Running -> JsonPrimitive("running")
            MultiAgentStatus.Interrupted -> JsonPrimitive("interrupted")
            is MultiAgentStatus.Completed -> buildJsonObject {
                put("completed", value.message?.let(::JsonPrimitive) ?: JsonNull)
            }
            is MultiAgentStatus.Errored -> buildJsonObject {
                put("errored", value.message)
            }
            MultiAgentStatus.Shutdown -> JsonPrimitive("shutdown")
            MultiAgentStatus.NotFound -> JsonPrimitive("not_found")
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): MultiAgentStatus {
        require(decoder is JsonDecoder) { "MultiAgentStatus can only be decoded as JSON." }
        return when (val element = decoder.decodeJsonElement()) {
            is JsonPrimitive -> when (element.content) {
                "pending_init" -> MultiAgentStatus.PendingInit
                "running" -> MultiAgentStatus.Running
                "interrupted" -> MultiAgentStatus.Interrupted
                "shutdown" -> MultiAgentStatus.Shutdown
                "not_found" -> MultiAgentStatus.NotFound
                else -> throw SerializationException("Unknown Agent status: ${element.content}")
            }

            is JsonObject -> when {
                "completed" in element -> MultiAgentStatus.Completed(
                    element.getValue("completed").let { value ->
                        if (value is JsonNull) null else value.jsonPrimitive.content
                    },
                )
                "errored" in element -> MultiAgentStatus.Errored(
                    element.getValue("errored").jsonPrimitive.content,
                )
                else -> throw SerializationException("Unknown Agent status object: $element")
            }

            else -> throw SerializationException("Agent status must be a string or object: $element")
        }
    }
}
