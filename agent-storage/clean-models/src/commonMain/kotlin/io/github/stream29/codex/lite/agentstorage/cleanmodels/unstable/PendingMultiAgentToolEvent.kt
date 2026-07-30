package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.tool.multiagent.FollowupTaskArgs
import io.github.stream29.codex.lite.tool.multiagent.InterruptAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.ListAgentsArgs
import io.github.stream29.codex.lite.tool.multiagent.SendMessageArgs
import io.github.stream29.codex.lite.tool.multiagent.SpawnAgentArgs
import io.github.stream29.codex.lite.tool.multiagent.WaitAgentArgs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Multi-agent operation waiting for execution. */
@Serializable
@SerialName("multi_agent")
public data class PendingMultiAgentToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val operation: PendingMultiAgentInvocation,
) : PendingToolEvent {
    override val toolName: String
        get() = operation.toolName
    override val toolNamespace: String? = null

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(operation.toFunctionCall(callId, itemId))
}

private fun PendingMultiAgentInvocation.toFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
): ResponseItem.FunctionCall =
    when (this) {
        is PendingMultiAgentInvocation.SpawnAgent ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "spawn_agent",
                serializer = SpawnAgentArgs.serializer(),
                arguments = arguments,
            )

        is PendingMultiAgentInvocation.SendMessage ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "send_message",
                serializer = SendMessageArgs.serializer(),
                arguments = arguments,
            )

        is PendingMultiAgentInvocation.FollowupTask ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "followup_task",
                serializer = FollowupTaskArgs.serializer(),
                arguments = arguments,
            )

        is PendingMultiAgentInvocation.WaitAgent ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "wait_agent",
                serializer = WaitAgentArgs.serializer(),
                arguments = arguments,
            )

        is PendingMultiAgentInvocation.InterruptAgent ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "interrupt_agent",
                serializer = InterruptAgentArgs.serializer(),
                arguments = arguments,
            )

        is PendingMultiAgentInvocation.ListAgents ->
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "list_agents",
                serializer = ListAgentsArgs.serializer(),
                arguments = arguments,
            )
    }
