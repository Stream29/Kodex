package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `suggest_subagent_task` call waiting for the user's decision. */
@Serializable
@SerialName("suggest_subagent_task")
public data class PendingSuggestSubagentTaskToolEvent(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val arguments: SuggestSubagentTaskArgs,
) : PendingToolEvent {
    override val toolName: String = "suggest_subagent_task"
    override val toolNamespace: String? = null

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            pendingFunctionCall(
                callId = callId,
                itemId = itemId,
                name = toolName,
                serializer = SuggestSubagentTaskArgs.serializer(),
                arguments = arguments,
            ),
        )
}
