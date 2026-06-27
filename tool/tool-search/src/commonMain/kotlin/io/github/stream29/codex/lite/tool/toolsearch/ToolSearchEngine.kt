package io.github.stream29.codex.lite.tool.toolsearch

import io.github.stream29.codex.lite.openai.LoadableToolSpec
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.utils.searchindex.SearchDocument
import io.github.stream29.codex.lite.utils.searchindex.SearchIndex
import io.github.stream29.codex.lite.utils.searchindex.createSearchIndex

public class ToolSearchEngine(
    private val documents: List<ToolSearchDocument>,
) {
    private val index: SearchIndex<ToolSearchDocument> = createSearchIndex(
        documents.map { document ->
            SearchDocument(
                value = document,
                text = document.searchText,
            )
        },
    )

    public fun search(arguments: SearchToolCallParams): List<LoadableToolSpec> {
        val query = arguments.query.trim()
        require(query.isNotEmpty()) { "query must not be empty" }

        val limit = arguments.limit ?: ToolSearchDefaultLimit
        require(limit > 0) { "limit must be greater than zero" }

        if (documents.isEmpty()) {
            return emptyList()
        }

        val results = index.search(query, limit)
        val namespacesByName = results
            .mapNotNull { it.output as? ResponsesApiToolWithNamespace }
            .groupBy { it.namespaceName }
            .mapValues { (_, tools) ->
                ResponsesApiNamespace(
                    name = tools.first().namespaceName,
                    description = tools.first().namespaceDescription,
                    tools = tools.map { it.tool },
                )
            }
            .toMutableMap()

        return results.mapNotNull { result ->
            when (val output = result.output) {
                is StandaloneResponsesApiTool -> output.tool
                is ResponsesApiToolWithNamespace -> namespacesByName.remove(output.namespaceName)
            }
        }
    }
}
