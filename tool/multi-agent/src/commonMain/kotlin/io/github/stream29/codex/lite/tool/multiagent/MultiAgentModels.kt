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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Decoded `fork_turns` selection for `spawn_agent`. */
@Serializable(with = SpawnForkModeSerializer::class)
public sealed interface SpawnForkMode {
    /** Do not copy any parent conversation history. */
    public data object None : SpawnForkMode

    /** Copy the complete parent conversation history. */
    public data object All : SpawnForkMode

    /** Copy only the most recent [turns] parent turns. */
    public data class Recent(public val turns: Int) : SpawnForkMode
}

/** Serializes [SpawnForkMode] as the string union accepted by `spawn_agent`. */
public object SpawnForkModeSerializer : KSerializer<SpawnForkMode> {
    override val descriptor: SerialDescriptor = JsonPrimitive.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SpawnForkMode) {
        val forkTurns = when (value) {
            SpawnForkMode.None -> "none"
            SpawnForkMode.All -> "all"
            is SpawnForkMode.Recent -> {
                if (value.turns <= 0) {
                    throw SerializationException(
                        "fork_turns must be `none`, `all`, or a positive integer string",
                    )
                }
                value.turns.toString()
            }
        }
        encoder.encodeString(forkTurns)
    }

    override fun deserialize(decoder: Decoder): SpawnForkMode {
        if (decoder is JsonDecoder) {
            return when (val element = decoder.decodeJsonElement()) {
                is JsonNull -> SpawnForkMode.All
                is JsonPrimitive -> {
                    if (!element.isString) {
                        throw SerializationException("fork_turns must be a string")
                    }
                    parseSpawnForkMode(element.content)
                }

                else -> throw SerializationException("fork_turns must be a string")
            }
        }
        return parseSpawnForkMode(decoder.decodeString())
    }
}

private fun parseSpawnForkMode(value: String): SpawnForkMode {
    val forkTurns = value.trim().ifEmpty { return SpawnForkMode.All }
    if (forkTurns.equals("none", ignoreCase = true)) return SpawnForkMode.None
    if (forkTurns.equals("all", ignoreCase = true)) return SpawnForkMode.All
    val turns = forkTurns.toIntOrNull()
    if (turns == null || turns <= 0) {
        throw SerializationException(
            "fork_turns must be `none`, `all`, or a positive integer string",
        )
    }
    return SpawnForkMode.Recent(turns)
}

/**
 * Input for `spawn_agent`.
 *
 * @property forkTurns Defaults to the full-history fork. The serializer also treats a legacy
 * `null` value as `all`.
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
    public val forkTurns: SpawnForkMode = SpawnForkMode.All,
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
 * `null` means the canonical full Agent path in [taskName] is the only display identity.
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

/** One model-facing live-Agent projection. */
@Serializable
public data class ListedAgent(
    @SerialName("agent_name")
    public val agentName: String,
    @SerialName("agent_status")
    public val agentStatus: MultiAgentStatus,
)

/** Whether an Agent currently has an active turn. */
@Serializable
public enum class MultiAgentStatus {
    @SerialName("running")
    Running,

    @SerialName("idle")
    Idle,
}
