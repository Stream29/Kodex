package io.github.stream29.codex.lite.agentruntime.plan

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.tool.contract.ToolName
import io.github.stream29.codex.lite.tool.contract.matches
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import io.github.stream29.codex.lite.tool.plan.PlanTools
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

private val updatePlanToolName: ToolName = ToolName.plain(PlanTools.Name)
/** Runtime layer that persists `update_plan` atomically with its tool output. */
public class PlanRuntime(
    private val delegate: CodexAgentRuntime,
) : CodexAgentRuntime by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        while (true) {
            delegate.resume().collect { send(it) }

            val calls = (state.value as? CodexAgentStateValue.ToolPending)
                ?.calls
                ?.filter { it.matches(updatePlanToolName) }
                .orEmpty()
            if (calls.isEmpty()) {
                return@channelFlow
            }

            for (call in calls) {
                completePlanCall(call)
            }
            if (state.value is CodexAgentStateValue.ToolPending) {
                return@channelFlow
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun completePlanCall(call: ResponseItem.ToolCall) {
        when (call) {
            is ResponseItem.FunctionCall -> completeFunctionCall(call)
            is ResponseItem.CustomToolCall -> completeToolCall(
                ResponseItem.CustomToolCallOutput(
                    callId = call.callId,
                    output = failure("update_plan handler received unsupported payload"),
                ),
            )
        }
    }

    private suspend fun completeFunctionCall(functionCall: ResponseItem.FunctionCall) {
        val plan = try {
            ToolBuilderJson.decodeFromString<UpdatePlanArgs>(functionCall.arguments)
        } catch (error: SerializationException) {
            completeToolCall(
                ResponseItem.FunctionCallOutput(
                    callId = functionCall.callId,
                    output = failure("failed to parse function arguments: ${error.message}"),
                ),
            )
            return
        }
        val output = ResponseItem.FunctionCallOutput(
            callId = functionCall.callId,
            output = FunctionCallOutputPayload.fromText("Plan updated").copy(success = true),
        )
        try {
            appendPlanUpdate(output, plan)
        } catch (error: IllegalArgumentException) {
            completeToolCall(
                ResponseItem.FunctionCallOutput(
                    callId = functionCall.callId,
                    output = failure(error.message ?: "update_plan failed"),
                ),
            )
        }
    }

    private fun failure(message: String): FunctionCallOutputPayload =
        FunctionCallOutputPayload.fromText(message).copy(success = false)
}

/** Adds `update_plan` handling; callers advertise [PlanTools.spec] through settings. */
public fun CodexAgentRuntime.planRuntime(): CodexAgentRuntime =
    PlanRuntime(this)
