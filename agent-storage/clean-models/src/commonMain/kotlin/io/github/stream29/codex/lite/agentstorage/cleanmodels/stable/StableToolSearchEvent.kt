package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed client deferred-tool search.
 *
 * @property arguments Tool-native search arguments.
 * @property result Tool-native search result.
 */
@Serializable
@SerialName("tool_search_event")
public data class StableToolSearchEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val arguments: SearchToolCallParams,
    public val result: ToolSearchResult,
) : StableCleanEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.ClientToolSearchCall(
                id = itemId,
                callId = callId,
                arguments = stableJsonElement(
                    serializer = SearchToolCallParams.serializer(),
                    value = arguments,
                ),
            ),
            ResponseItem.ClientToolSearchOutput(
                callId = callId,
                status = "completed",
                tools = when (result) {
                    is ToolSearchResult.Success -> result.tools
                    is ToolSearchResult.InvalidArguments -> emptyList()
                },
            ),
        )
}
