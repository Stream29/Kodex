package io.github.stream29.kodex.tool.plan

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.appendPlanUpdate
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.builder.ToolBuilderJson
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool

/**
 * Creates the ordinary `update_plan` tool bound to this Agent.
 *
 * A successful call updates its parsed plan and completes its tool output
 * through [appendPlanUpdate]. The generic tool runtime therefore must
 * not append the returned output again when the call is no longer pending.
 */
public fun KodexAgentState.updatePlanTool(): Tool =
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
