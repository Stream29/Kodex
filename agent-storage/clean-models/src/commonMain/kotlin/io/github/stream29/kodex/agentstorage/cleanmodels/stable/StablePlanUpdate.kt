package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.UpdatePlanArgs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable completed `update_plan` interaction.
 *
 * The settings timeline remains the source of truth for the active plan.
 */
@Serializable
@SerialName("plan_update")
public data class StablePlanUpdate(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val arguments: UpdatePlanArgs,
) : StableCleanEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "update_plan",
                serializer = UpdatePlanArgs.serializer(),
                arguments = arguments,
            ),
            stableTextOutput(
                callId = callId,
                text = "Plan updated",
                success = true,
            ),
        )
}
