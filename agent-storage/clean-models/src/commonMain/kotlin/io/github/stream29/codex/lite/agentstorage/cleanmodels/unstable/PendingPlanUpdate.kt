package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `update_plan` call waiting for completion. */
@Serializable
@SerialName("plan_update")
public data class PendingPlanUpdate(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: UpdatePlanArgs,
) : PendingToolEvent {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "update_plan",
                serializer = UpdatePlanArgs.serializer(),
                arguments = arguments,
            ),
        )
}
