package io.github.stream29.codex.lite.tool.toolsearch

import io.github.stream29.codex.lite.tool.contract.FreeformTool
import io.github.stream29.codex.lite.tool.contract.ResponsesApiNamespace
import io.github.stream29.codex.lite.tool.contract.ResponsesApiTool
import io.github.stream29.codex.lite.tool.contract.ToolSpec
import kotlinx.schema.json.ApplicatorContainer
import kotlinx.schema.json.ArrayContainer
import kotlinx.schema.json.CommonSchemaAttributes
import kotlinx.schema.json.PropertiesContainer
import kotlinx.schema.json.PropertyDefinition

/**
 * @param sourceInfo Nullable because the generated documents may have no source
 * label for the tool_search description; `null` means no source label.
 */
public fun ToolSpec.toToolSearchDocuments(
    sourceInfo: ToolSearchSourceInfo? = null,
): List<ToolSearchDocument> =
    when (this) {
        is ResponsesApiTool -> listOf(
            ToolSearchDocument(
                searchText = defaultToolSearchText(),
                output = StandaloneResponsesApiTool(copy(deferLoading = true)),
                sourceInfo = sourceInfo,
            ),
        )
        is ResponsesApiNamespace -> {
            val namespaceDescription = description.ifBlank { defaultNamespaceDescription(name) }
            tools.map { tool ->
                when (tool) {
                    is ResponsesApiTool -> ToolSearchDocument(
                        searchText = tool.defaultToolSearchText(
                            namespaceName = name,
                            namespaceDescription = namespaceDescription,
                        ),
                        output = ResponsesApiToolWithNamespace(
                            namespaceName = name,
                            namespaceDescription = namespaceDescription,
                            tool = tool.copy(deferLoading = true),
                        ),
                        sourceInfo = sourceInfo,
                    )
                }
            }
        }
        is ToolSpec.ToolSearch,
        is ToolSpec.ImageGeneration,
        is ToolSpec.WebSearch,
        is FreeformTool,
        -> emptyList()
    }

private fun ResponsesApiTool.defaultToolSearchText(
    namespaceName: String,
    namespaceDescription: String,
): String {
    val parts = mutableListOf<String>()
    parts.pushSearchPart(namespaceName)
    parts.pushSearchPart(namespaceDescription)
    appendFunctionSearchText(this, parts)
    return parts.joinToString(" ")
}

private fun ResponsesApiTool.defaultToolSearchText(): String {
    val parts = mutableListOf<String>()
    appendFunctionSearchText(this, parts)
    return parts.joinToString(" ")
}

private fun defaultNamespaceDescription(namespaceName: String): String =
    "Tools in the $namespaceName namespace."

private fun appendFunctionSearchText(tool: ResponsesApiTool, parts: MutableList<String>) {
    parts.pushSearchPart(tool.name)
    parts.pushSearchPart(tool.name.replace('_', ' '))
    parts.pushSearchPart(tool.description)
    appendSchemaSearchText(tool.parameters, parts)
}

private fun appendSchemaSearchText(schema: PropertyDefinition, parts: MutableList<String>) {
    (schema as? CommonSchemaAttributes)?.description?.let(parts::pushSearchPart)

    (schema as? PropertiesContainer)?.properties.orEmpty().forEach { (name, property) ->
        parts.pushSearchPart(name)
        appendSchemaSearchText(property, parts)
    }

    (schema as? ArrayContainer)?.items?.let { appendSchemaSearchText(it, parts) }

    (schema as? ApplicatorContainer)?.anyOf.orEmpty().forEach { appendSchemaSearchText(it, parts) }
}

private fun MutableList<String>.pushSearchPart(part: String) {
    val trimmed = part.trim()
    if (trimmed.isNotEmpty()) {
        add(trimmed)
    }
}
