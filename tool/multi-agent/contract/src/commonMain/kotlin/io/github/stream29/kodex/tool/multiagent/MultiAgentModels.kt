package io.github.stream29.kodex.tool.multiagent

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input for `spawn_agent`.
 *
 * @property model Nullable because a child normally inherits its parent model; `null` means inherit.
 * @property reasoningEffort Nullable because a child normally inherits its parent effort; `null` means inherit.
 *
 * Unknown persisted fields such as legacy `fork_turns` and `service_tier` are
 * intentionally ignored by the shared JSON codec.
 */
@Serializable
public data class SpawnAgentArgs(
    @SerialName("task_name")
    public val taskName: String,
    public val message: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val model: OpenAiModelId? = null,
    @SerialName("reasoning_effort")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val reasoningEffort: ReasoningEffort? = null,
)

/**
 * Successful `spawn_agent` result.
 *
 * @property nickname Nullable because Kodex does not require a separate nickname;
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
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
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
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
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
