package io.github.stream29.kodex.agentstorage.cleanmodels.stable.index

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableFunctionCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableJsonOutput
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.stableTextOutput
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable clean projection of a completed subagent task suggestion. */
@Serializable
@SerialName("suggest_subagent_task_tool_event")
public data class StableSuggestSubagentTaskToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val arguments: SuggestSubagentTaskArgs,
    public val result: StableSuggestSubagentTaskResult,
) : StableIndexEvent.CompletedTool, CompactionRetainedItem {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            stableFunctionCall(
                callId = callId,
                itemId = itemId,
                name = "suggest_subagent_task",
                serializer = SuggestSubagentTaskArgs.serializer(),
                arguments = arguments,
            ),
            result.toFunctionOutput(callId),
        )
}

@Serializable
public sealed interface StableSuggestSubagentTaskResult {
    @Serializable
    @SerialName("completed")
    public data class Completed(
        public val response: SuggestSubagentTaskResponse,
    ) : StableSuggestSubagentTaskResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableSuggestSubagentTaskResult
}

private fun StableSuggestSubagentTaskResult.toFunctionOutput(
    callId: String,
): ResponseItem.FunctionCallOutput =
    when (this) {
        is StableSuggestSubagentTaskResult.Completed ->
            when (val response = response) {
                is SuggestSubagentTaskResponse.Accepted ->
                    stableJsonOutput(
                        callId = callId,
                        serializer = SuggestSubagentTaskResponse.Accepted.serializer(),
                        result = response,
                        success = true,
                    )
                is SuggestSubagentTaskResponse.Rejected ->
                    stableJsonOutput(
                        callId = callId,
                        serializer = SuggestSubagentTaskResponse.Rejected.serializer(),
                        result = response,
                        success = true,
                    )
            }

        is StableSuggestSubagentTaskResult.Failure ->
            stableTextOutput(
                callId = callId,
                text = message,
                success = false,
            )
    }
