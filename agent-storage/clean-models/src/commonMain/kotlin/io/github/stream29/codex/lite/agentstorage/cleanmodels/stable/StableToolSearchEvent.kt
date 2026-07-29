package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchExecution
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed deferred-tool search.
 *
 * @property execution Side that executed the search.
 * @property arguments Tool-native search arguments.
 * @property result Tool-native search result.
 */
@Serializable
@SerialName("tool_search_event")
public data class StableToolSearchEvent(
    public val execution: ToolSearchExecution,
    public val arguments: SearchToolCallParams,
    public val result: ToolSearchResult,
) : StableCleanEvent.CompletedTool
