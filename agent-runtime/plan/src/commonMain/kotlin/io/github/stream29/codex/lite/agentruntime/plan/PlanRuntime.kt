package io.github.stream29.codex.lite.agentruntime.plan

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.hook.toolutils.runPreToolUse
import io.github.stream29.codex.lite.hook.toolutils.runPostToolUse
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ModeKind
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
import kotlinx.serialization.SerializationException

private val updatePlanToolName: ToolName = ToolName.plain(PlanTools.Name)
/** Runtime layer that persists `update_plan` atomically with its tool output. */
public class PlanRuntime(
    private val delegate: CodexAgentRuntime,
    private val toolHooks: ToolHooks,
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

            is ResponseItem.ClientToolSearchCall ->
                error("Client tool-search calls are handled by CodexToolRuntime.")
        }
    }

    private suspend fun completeFunctionCall(functionCall: ResponseItem.FunctionCall) {
        when (val result = toolHooks.runPreToolUse(delegate.storage, functionCall)) {
            is PreToolUseResult.Block -> {
                completeToolCall(
                    ResponseItem.FunctionCallOutput(
                        callId = functionCall.callId,
                        output = failure(result.reason),
                    ),
                )
                return
            }

            PreToolUseResult.Continue -> Unit
        }
        if (delegate.storage.settings.latestValue().collaborationMode == ModeKind.Plan) {
            completeToolCall(
                ResponseItem.FunctionCallOutput(
                    callId = functionCall.callId,
                    output = failure(
                        "update_plan is a TODO/checklist tool and is not allowed in Plan mode.",
                    ),
                ),
            )
            return
        }
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
        toolHooks.runPostToolUse(
            storage = delegate.storage,
            call = functionCall,
            output = output,
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

/** Adds `update_plan` handling; request composition advertises [PlanTools.spec] in default mode. */
public fun CodexAgentRuntime.planRuntime(
    toolHooks: ToolHooks,
): CodexAgentRuntime = PlanRuntime(this, toolHooks)
