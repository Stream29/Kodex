package io.github.stream29.codex.lite.tool.plan

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.tool.builder.functionOutputTool
import io.github.stream29.codex.lite.tool.contract.Tool

/**
 * Creates the ordinary `update_plan` tool bound to this Agent.
 *
 * A successful call commits its parsed plan and tool output together through
 * [CodexAgentState.appendPlanUpdate]. The generic tool runtime therefore must
 * not append the returned output again when the call is no longer pending.
 */
public fun CodexAgentState.updatePlanTool(): Tool =
    functionOutputTool(
        spec = PlanTools.spec,
        inputDeserializer = UpdatePlanArgs.serializer(),
    ) { callId, plan ->
        val output = FunctionCallOutputPayload.fromText("Plan updated").copy(success = true)
        try {
            appendPlanUpdate(
                output = ResponseItem.FunctionCallOutput(
                    callId = callId,
                    output = output,
                ),
                plan = plan,
            )
            output
        } catch (error: IllegalArgumentException) {
            FunctionCallOutputPayload.fromText(
                error.message ?: "update_plan failed",
            ).copy(success = false)
        }
    }
