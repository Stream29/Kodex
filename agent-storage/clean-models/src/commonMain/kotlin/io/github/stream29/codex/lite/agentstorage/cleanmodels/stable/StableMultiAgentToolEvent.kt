package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentResult
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsResult
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentResult
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of one completed Multi-agent tool interaction.
 *
 * Each operation retains the tool contract's own arguments and result model.
 */
@Serializable
@SerialName("multi_agent_tool_event")
public data class StableMultiAgentToolEvent(
    public val operation: StableMultiAgentOperation,
) : StableCleanEvent.CompletedTool

/** One strongly typed Multi-agent operation. */
@Serializable
public sealed interface StableMultiAgentOperation {
    @Serializable
    @SerialName("spawn_agent")
    public data class SpawnAgent(
        public val arguments: SpawnAgentArgs,
        public val result: StableSpawnAgentResult,
    ) : StableMultiAgentOperation

    @Serializable
    @SerialName("send_message")
    public data class SendMessage(
        public val arguments: SendMessageArgs,
        public val result: StableAgentDeliveryResult,
    ) : StableMultiAgentOperation

    @Serializable
    @SerialName("followup_task")
    public data class FollowupTask(
        public val arguments: FollowupTaskArgs,
        public val result: StableAgentDeliveryResult,
    ) : StableMultiAgentOperation

    @Serializable
    @SerialName("wait_agent")
    public data class WaitAgent(
        public val arguments: WaitAgentArgs,
        public val result: StableWaitAgentResult,
    ) : StableMultiAgentOperation

    @Serializable
    @SerialName("interrupt_agent")
    public data class InterruptAgent(
        public val arguments: InterruptAgentArgs,
        public val result: StableInterruptAgentResult,
    ) : StableMultiAgentOperation

    @Serializable
    @SerialName("list_agents")
    public data class ListAgents(
        public val arguments: ListAgentsArgs,
        public val result: StableListAgentsResult,
    ) : StableMultiAgentOperation
}

@Serializable
public sealed interface StableSpawnAgentResult {
    @Serializable
    @SerialName("success")
    public data class Success(
        public val value: SpawnAgentResult,
    ) : StableSpawnAgentResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableSpawnAgentResult
}

@Serializable
public sealed interface StableAgentDeliveryResult {
    @Serializable
    @SerialName("success")
    public data class Success(
        public val output: String,
    ) : StableAgentDeliveryResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableAgentDeliveryResult
}

@Serializable
public sealed interface StableWaitAgentResult {
    @Serializable
    @SerialName("success")
    public data class Success(
        public val value: WaitAgentResult,
    ) : StableWaitAgentResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableWaitAgentResult
}

@Serializable
public sealed interface StableInterruptAgentResult {
    @Serializable
    @SerialName("success")
    public data class Success(
        public val value: InterruptAgentResult,
    ) : StableInterruptAgentResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableInterruptAgentResult
}

@Serializable
public sealed interface StableListAgentsResult {
    @Serializable
    @SerialName("success")
    public data class Success(
        public val value: ListAgentsResult,
    ) : StableListAgentsResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableListAgentsResult
}
