package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.multiagent.FollowupTaskArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentArgs
import io.github.stream29.kodex.tool.multiagent.InterruptAgentResult
import io.github.stream29.kodex.tool.multiagent.ListAgentsArgs
import io.github.stream29.kodex.tool.multiagent.ListAgentsResult
import io.github.stream29.kodex.tool.multiagent.SendMessageArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentArgs
import io.github.stream29.kodex.tool.multiagent.SpawnAgentResult
import io.github.stream29.kodex.tool.multiagent.WaitAgentArgs
import io.github.stream29.kodex.tool.multiagent.WaitAgentResult
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
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val operation: StableMultiAgentOperation,
) : StableCleanEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            operation.toFunctionCall(callId, itemId),
            operation.toFunctionOutput(callId),
        )
}

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

private fun StableMultiAgentOperation.toFunctionCall(
    callId: String,
    itemId: ResponseItemId?,
): ResponseItem.FunctionCall =
    when (this) {
        is StableMultiAgentOperation.SpawnAgent ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "spawn_agent",
                serializer = SpawnAgentArgs.serializer(),
                arguments = arguments,
            )

        is StableMultiAgentOperation.SendMessage ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "send_message",
                serializer = SendMessageArgs.serializer(),
                arguments = arguments,
            )

        is StableMultiAgentOperation.FollowupTask ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "followup_task",
                serializer = FollowupTaskArgs.serializer(),
                arguments = arguments,
            )

        is StableMultiAgentOperation.WaitAgent ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "wait_agent",
                serializer = WaitAgentArgs.serializer(),
                arguments = arguments,
            )

        is StableMultiAgentOperation.InterruptAgent ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "interrupt_agent",
                serializer = InterruptAgentArgs.serializer(),
                arguments = arguments,
            )

        is StableMultiAgentOperation.ListAgents ->
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "list_agents",
                serializer = ListAgentsArgs.serializer(),
                arguments = arguments,
            )
    }

private fun StableMultiAgentOperation.toFunctionOutput(
    callId: String,
): ResponseItem.FunctionCallOutput =
    when (this) {
        is StableMultiAgentOperation.SpawnAgent ->
            when (val result = result) {
                is StableSpawnAgentResult.Success ->
                    stableJsonOutput(
                        callId = callId,
                        serializer = SpawnAgentResult.serializer(),
                        result = result.value,
                        success = true,
                    )

                is StableSpawnAgentResult.Failure ->
                    stableFailureOutput(callId, result.message)
            }

        is StableMultiAgentOperation.SendMessage ->
            result.toFunctionOutput(callId)

        is StableMultiAgentOperation.FollowupTask ->
            result.toFunctionOutput(callId)

        is StableMultiAgentOperation.WaitAgent ->
            when (val result = result) {
                is StableWaitAgentResult.Success ->
                    stableJsonOutput(
                        callId = callId,
                        serializer = WaitAgentResult.serializer(),
                        result = result.value,
                        success = true,
                    )

                is StableWaitAgentResult.Failure ->
                    stableFailureOutput(callId, result.message)
            }

        is StableMultiAgentOperation.InterruptAgent ->
            when (val result = result) {
                is StableInterruptAgentResult.Success ->
                    stableJsonOutput(
                        callId = callId,
                        serializer = InterruptAgentResult.serializer(),
                        result = result.value,
                        success = true,
                    )

                is StableInterruptAgentResult.Failure ->
                    stableFailureOutput(callId, result.message)
            }

        is StableMultiAgentOperation.ListAgents ->
            when (val result = result) {
                is StableListAgentsResult.Success ->
                    stableJsonOutput(
                        callId = callId,
                        serializer = ListAgentsResult.serializer(),
                        result = result.value,
                        success = true,
                    )

                is StableListAgentsResult.Failure ->
                    stableFailureOutput(callId, result.message)
            }
    }

private fun StableAgentDeliveryResult.toFunctionOutput(
    callId: String,
): ResponseItem.FunctionCallOutput =
    when (this) {
        is StableAgentDeliveryResult.Success ->
            stableTextOutput(
                callId = callId,
                text = output,
                success = true,
            )

        is StableAgentDeliveryResult.Failure ->
            stableFailureOutput(callId, message)
    }

private fun stableFailureOutput(
    callId: String,
    message: String,
): ResponseItem.FunctionCallOutput =
    stableTextOutput(
        callId = callId,
        text = message,
        success = false,
    )
