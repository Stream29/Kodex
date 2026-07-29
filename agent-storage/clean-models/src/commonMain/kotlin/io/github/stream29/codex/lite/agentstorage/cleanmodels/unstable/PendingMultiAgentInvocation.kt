package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Input-only form of one pending Multi-agent operation. */
@Serializable
public sealed interface PendingMultiAgentInvocation {
    /** `spawn_agent` input. */
    @Serializable
    @SerialName("spawn_agent")
    public data class SpawnAgent(
        @SerialName("task_name")
        public val taskName: String,
        public val message: String,
        @SerialName("fork_turns")
        public val forkTurns: PendingAgentForkMode,
        public val model: String? = null,
        @SerialName("reasoning_effort")
        public val reasoningEffort: String? = null,
        @SerialName("service_tier")
        public val serviceTier: String? = null,
    ) : PendingMultiAgentInvocation

    /** `send_message` input. */
    @Serializable
    @SerialName("send_message")
    public data class SendMessage(
        public val target: String,
        public val message: String,
    ) : PendingMultiAgentInvocation

    /** `followup_task` input. */
    @Serializable
    @SerialName("followup_task")
    public data class FollowupTask(
        public val target: String,
        public val message: String,
    ) : PendingMultiAgentInvocation

    /** `wait_agent` input. */
    @Serializable
    @SerialName("wait_agent")
    public data class WaitAgent(
        @SerialName("timeout_ms")
        public val timeoutMillis: Long? = null,
    ) : PendingMultiAgentInvocation

    /** `interrupt_agent` input. */
    @Serializable
    @SerialName("interrupt_agent")
    public data class InterruptAgent(
        public val target: String,
    ) : PendingMultiAgentInvocation

    /** `list_agents` input. */
    @Serializable
    @SerialName("list_agents")
    public data class ListAgents(
        @SerialName("path_prefix")
        public val pathPrefix: String? = null,
    ) : PendingMultiAgentInvocation
}

/** Parent-history selection used by a pending `spawn_agent` call. */
@Serializable
public sealed interface PendingAgentForkMode {
    /** Do not copy parent turns. */
    @Serializable
    @SerialName("none")
    public data object None : PendingAgentForkMode

    /** Copy all parent turns. */
    @Serializable
    @SerialName("all")
    public data object All : PendingAgentForkMode

    /** Copy only the most recent [turns]. */
    @Serializable
    @SerialName("recent")
    public data class Recent(
        public val turns: Int,
    ) : PendingAgentForkMode
}
