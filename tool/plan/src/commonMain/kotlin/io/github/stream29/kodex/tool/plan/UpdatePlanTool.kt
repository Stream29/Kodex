package io.github.stream29.kodex.tool.plan

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.appendPlanUpdate
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.builder.ToolBuilderJson
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.typedTool

public actual fun KodexAgentState.updatePlanTool(): Tool =
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
