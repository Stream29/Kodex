package io.github.stream29.codex.lite.tool.plan

import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.typedTool
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Creates the ordinary `update_plan` tool bound to this Agent.
 *
 * A successful call commits its parsed plan and tool output together through
 * [CodexAgentState.appendPlanUpdate]. The generic tool runtime therefore must
 * not append the returned output again when the call is no longer pending.
 */
public fun CodexAgentState.updatePlanTool(): Tool =
    typedTool(
        spec = PlanTools.spec,
        select = { it as? PendingPlanUpdate },
    ) { pending ->
        val completed = StablePlanUpdate(
            callId = pending.callId,
            itemId = pending.itemId,
            arguments = pending.arguments,
        )
        try {
            appendPlanUpdate(completed)
            completed
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "update_plan failed"
            StableTextToolEvent(
                callId = pending.callId,
                itemId = pending.itemId,
                name = PlanTools.Name,
                arguments = ToolBuilderJson.encodeToJsonElement(
                    UpdatePlanArgs.serializer(),
                    pending.arguments,
                ),
                result = message,
                success = false,
            )
        }
    }
