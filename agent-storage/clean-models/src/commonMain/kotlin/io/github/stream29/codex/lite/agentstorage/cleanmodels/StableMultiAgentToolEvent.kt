package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of one completed Multi-agent tool interaction.
 *
 * Each [operation] variant owns its arguments and legal result type, preventing
 * payloads from different Multi-agent tools from being mixed.
 */
@Serializable
@SerialName("multi_agent_tool_event")
public data class StableMultiAgentToolEvent(
    public val operation: StableMultiAgentOperation,
) : StableToolEvent

/** One strongly typed Multi-agent operation. */
@Serializable
public sealed interface StableMultiAgentOperation {
    /** `spawn_agent` interaction. */
    @Serializable
    @SerialName("spawn_agent")
    public data class SpawnAgent(
        @SerialName("task_name")
        public val taskName: String,
        public val message: String,
        @SerialName("fork_turns")
        public val forkTurns: StableAgentForkMode,
        public val model: String? = null,
        @SerialName("reasoning_effort")
        public val reasoningEffort: String? = null,
        @SerialName("service_tier")
        public val serviceTier: String? = null,
        public val result: StableSpawnAgentResult,
    ) : StableMultiAgentOperation

    /** `send_message` interaction. */
    @Serializable
    @SerialName("send_message")
    public data class SendMessage(
        public val target: String,
        public val message: String,
        public val result: StableAgentDeliveryResult,
    ) : StableMultiAgentOperation

    /** `followup_task` interaction. */
    @Serializable
    @SerialName("followup_task")
    public data class FollowupTask(
        public val target: String,
        public val message: String,
        public val result: StableAgentDeliveryResult,
    ) : StableMultiAgentOperation

    /** `wait_agent` interaction. */
    @Serializable
    @SerialName("wait_agent")
    public data class WaitAgent(
        @SerialName("timeout_ms")
        public val timeoutMillis: Long? = null,
        public val result: StableWaitAgentResult,
    ) : StableMultiAgentOperation

    /** `interrupt_agent` interaction. */
    @Serializable
    @SerialName("interrupt_agent")
    public data class InterruptAgent(
        public val target: String,
        public val result: StableInterruptAgentResult,
    ) : StableMultiAgentOperation

    /** `list_agents` interaction. */
    @Serializable
    @SerialName("list_agents")
    public data class ListAgents(
        @SerialName("path_prefix")
        public val pathPrefix: String? = null,
        public val result: StableListAgentsResult,
    ) : StableMultiAgentOperation
}

/** Parent-history selection used when spawning an Agent. */
@Serializable
public sealed interface StableAgentForkMode {
    /** Do not copy parent turns. */
    @Serializable
    @SerialName("none")
    public data object None : StableAgentForkMode

    /** Copy all parent turns. */
    @Serializable
    @SerialName("all")
    public data object All : StableAgentForkMode

    /** Copy only the most recent [turns]. */
    @Serializable
    @SerialName("recent")
    public data class Recent(
        public val turns: Int,
    ) : StableAgentForkMode
}

/** Result of `spawn_agent`. */
@Serializable
public sealed interface StableSpawnAgentResult {
    /** Child Agent was created. */
    @Serializable
    @SerialName("success")
    public data class Success(
        @SerialName("agent_path")
        public val agentPath: String,
        public val nickname: String? = null,
    ) : StableSpawnAgentResult

    /** Child Agent creation failed. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableSpawnAgentResult
}

/** Result shared by message and follow-up delivery. */
@Serializable
public sealed interface StableAgentDeliveryResult {
    /** Message was delivered to the target Agent. */
    @Serializable
    @SerialName("success")
    public data object Success : StableAgentDeliveryResult

    /** Message delivery failed. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableAgentDeliveryResult
}

/** Result of `wait_agent`. */
@Serializable
public sealed interface StableWaitAgentResult {
    /** Wait completed either because activity arrived or the timeout elapsed. */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val message: String,
        @SerialName("timed_out")
        public val timedOut: Boolean,
    ) : StableWaitAgentResult

    /** Waiting failed before a normal result was produced. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableWaitAgentResult
}

/** Result of `interrupt_agent`. */
@Serializable
public sealed interface StableInterruptAgentResult {
    /** Target interruption completed. */
    @Serializable
    @SerialName("success")
    public data class Success(
        @SerialName("previous_status")
        public val previousStatus: StableAgentStatus,
    ) : StableInterruptAgentResult

    /** Target interruption failed. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableInterruptAgentResult
}

/** Result of `list_agents`. */
@Serializable
public sealed interface StableListAgentsResult {
    /** Live Agent projections returned by the tool. */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val agents: List<StableListedAgent>,
    ) : StableListAgentsResult

    /** Agent listing failed. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableListAgentsResult
}

/** One live Agent returned by `list_agents`. */
@Serializable
public data class StableListedAgent(
    @SerialName("agent_path")
    public val agentPath: String,
    public val status: StableAgentStatus,
)

/** Whether an Agent had an active turn when the operation completed. */
@Serializable
public enum class StableAgentStatus {
    @SerialName("running")
    Running,

    @SerialName("idle")
    Idle,
}
