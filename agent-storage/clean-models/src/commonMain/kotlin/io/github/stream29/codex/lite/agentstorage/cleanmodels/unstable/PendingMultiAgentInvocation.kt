package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Input-only form of one pending Multi-agent operation. */
@Serializable
public sealed interface PendingMultiAgentInvocation {
    /** Model-visible fixed tool name for this operation. */
    public val toolName: String

    /** `spawn_agent` input. */
    @Serializable
    @SerialName("spawn_agent")
    public data class SpawnAgent(
        public val arguments: SpawnAgentArgs,
    ) : PendingMultiAgentInvocation {
        override val toolName: String = "spawn_agent"
    }

    /** `send_message` input. */
    @Serializable
    @SerialName("send_message")
    public data class SendMessage(
        public val arguments: SendMessageArgs,
    ) : PendingMultiAgentInvocation {
        override val toolName: String = "send_message"
    }

    /** `followup_task` input. */
    @Serializable
    @SerialName("followup_task")
    public data class FollowupTask(
        public val arguments: FollowupTaskArgs,
    ) : PendingMultiAgentInvocation {
        override val toolName: String = "followup_task"
    }

    /** `wait_agent` input. */
    @Serializable
    @SerialName("wait_agent")
    public data class WaitAgent(
        public val arguments: WaitAgentArgs,
    ) : PendingMultiAgentInvocation {
        override val toolName: String = "wait_agent"
    }

    /** `interrupt_agent` input. */
    @Serializable
    @SerialName("interrupt_agent")
    public data class InterruptAgent(
        public val arguments: InterruptAgentArgs,
    ) : PendingMultiAgentInvocation {
        override val toolName: String = "interrupt_agent"
    }

    /** `list_agents` input. */
    @Serializable
    @SerialName("list_agents")
    public data class ListAgents(
        public val arguments: ListAgentsArgs,
    ) : PendingMultiAgentInvocation {
        override val toolName: String = "list_agents"
    }
}
