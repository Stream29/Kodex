package io.github.stream29.codex.lite.tool.toolsearch

import io.github.stream29.codex.lite.openai.ToolSpec

public object ToolSearchTools {
    public fun createToolSearchSpec(
        searchableSources: List<ToolSearchSourceInfo> = emptyList(),
        defaultLimit: Int = ToolSearchDefaultLimit,
    ): ToolSpec.ToolSearch =
        ToolSpec.ToolSearch(
            execution = "client",
            description = createDescription(searchableSources),
            parameters = if (defaultLimit == ToolSearchDefaultLimit) {
                ToolSearchParametersSchema
            } else {
                createParametersSchema(defaultLimit)
            },
        )

    private fun createParametersSchema(defaultLimit: Int) =
        kotlinx.schema.json.PropertyBuilder().obj {
            additionalProperties = false
            property("query") {
                required = true
                string { description = "Search query for deferred tools." }
            }
            property("limit") {
                integer { description = "Maximum number of tools to return. Defaults to $defaultLimit." }
            }
        }

    private fun createDescription(searchableSources: List<ToolSearchSourceInfo>): String {
        val sourceDescriptions = searchableSources
            .fold(linkedMapOf<String, String?>()) { descriptions: LinkedHashMap<String, String?>, source ->
                val existing = descriptions[source.name]
                if (!descriptions.containsKey(source.name) || existing == null) {
                    descriptions[source.name] = source.description
                }
                descriptions
            }
            .entries
            .joinToString("\n") { (name, description) ->
                if (description == null) "- $name" else "- $name: $description"
            }
            .ifEmpty { "None currently enabled." }

        return "# Tool discovery\n\n" +
            "Searches over deferred tool metadata with BM25 and exposes matching tools for the next model call.\n\n" +
            "You have access to tools from the following sources:\n" +
            sourceDescriptions +
            "\nSome of the tools may not have been provided to you upfront, and you should use this tool (`$ToolSearchToolName`) to search for the required tools. " +
            "For MCP tool discovery, always use `$ToolSearchToolName` instead of `list_mcp_resources` or `list_mcp_resource_templates`."
    }
}
