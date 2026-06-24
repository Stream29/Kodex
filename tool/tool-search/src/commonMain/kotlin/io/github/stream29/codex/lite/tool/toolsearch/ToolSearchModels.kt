package io.github.stream29.codex.lite.tool.toolsearch

import io.github.stream29.codex.lite.tool.contract.ResponsesApiTool
import kotlinx.serialization.Serializable

/**
 * Flattened form of Rust `ToolSearchInfo` plus `ToolSearchEntry`.
 *
 * Rust keeps source metadata outside the indexed entry because its handler splits
 * those collections during construction. Kotlin keeps one DTO because the search
 * adapter already has a generic document layer and only behavior needs to match.
 *
 * @property sourceInfo Nullable because some tools have no external source label;
 * `null` means the tool is still searchable but contributes no source description.
 */
public data class ToolSearchDocument(
    public val searchText: String,
    public val output: ToolSearchDocumentOutput,
    public val sourceInfo: ToolSearchSourceInfo? = null,
)

public sealed interface ToolSearchDocumentOutput

public data class StandaloneResponsesApiTool(
    public val tool: ResponsesApiTool,
) : ToolSearchDocumentOutput

public data class ResponsesApiToolWithNamespace(
    public val namespaceName: String,
    public val namespaceDescription: String,
    public val tool: ResponsesApiTool,
) : ToolSearchDocumentOutput

/**
 * @property description Nullable because source labels may not provide extra text;
 * `null` means only the source name should be rendered in the tool_search spec.
 */
public data class ToolSearchSourceInfo(
    public val name: String,
    public val description: String? = null,
)

/**
 * @property limit Nullable because the model may omit it; `null` means use
 * `ToolSearchDefaultLimit`.
 */
@Serializable
public data class SearchToolCallParams(
    public val query: String,
    public val limit: Int? = null,
)
