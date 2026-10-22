@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.toolsearch

public expect class ToolSearchEngine(
    documents: List<ToolSearchDocument>,
) {
    public fun search(arguments: SearchToolCallParams): ToolSearchResult
}
