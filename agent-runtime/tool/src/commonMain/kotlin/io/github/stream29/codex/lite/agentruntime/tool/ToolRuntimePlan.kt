package io.github.stream29.codex.lite.agentruntime.tool

import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchDocument
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchEngine
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchSourceInfo
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.tool.toolsearch.toToolSearchDocuments

/** Controls whether a local tool schema is initially visible to the model. */
public enum class ToolExposure {
    /** Send the tool schema in every initial Responses request. */
    Direct,

    /** Expose the tool schema only through a client `tool_search` result. */
    Deferred,
}

/**
 * One locally executable [tool] and its model-facing [exposure].
 *
 * @property sourceInfo Nullable because a deferred tool may have no source
 * label; `null` means it is searchable but contributes no source description
 * to the generated `tool_search` schema.
 */
public data class ToolRuntimeEntry(
    public val tool: Tool,
    public val exposure: ToolExposure = ToolExposure.Direct,
    public val sourceInfo: ToolSearchSourceInfo? = null,
)

/**
 * Immutable tool setup for one agent runtime.
 *
 * [modelVisibleSpecs] is supplied by the caller to `CodexAgentSettings.tools`.
 * Every [ToolRuntimeEntry.tool] remains locally executable through the plan,
 * including deferred tools.
 */
public class ToolRuntimePlan internal constructor(
    public val modelVisibleSpecs: List<ToolSpec>,
    internal val tools: List<Tool>,
    internal val toolSearchEngine: ToolSearchEngine,
)

/**
 * Builds model-visible schemas and a deferred-tool search index from [entries].
 *
 * [toolSearchEnabled] must reflect the selected model and provider capability.
 * When it is false, deferred entries are promoted to direct exposure so their
 * handlers remain reachable.
 */
public fun toolRuntimePlan(
    entries: List<ToolRuntimeEntry>,
    toolSearchEnabled: Boolean,
): ToolRuntimePlan {
    val searchDocuments = mutableListOf<ToolSearchDocument>()
    val modelVisibleSpecs = buildList {
        for (entry in entries) {
            val deferredDocuments = if (entry.exposure == ToolExposure.Deferred && toolSearchEnabled) {
                entry.tool.spec.toToolSearchDocuments(entry.sourceInfo)
            } else {
                emptyList()
            }
            if (deferredDocuments.isEmpty()) {
                add(entry.tool.spec)
            } else {
                searchDocuments += deferredDocuments
            }
        }
        if (searchDocuments.isNotEmpty()) {
            add(
                ToolSearchTools.createToolSearchSpec(
                    searchableSources = searchDocuments.mapNotNull { document -> document.sourceInfo },
                ),
            )
        }
    }
    return ToolRuntimePlan(
        modelVisibleSpecs = modelVisibleSpecs,
        tools = entries.map { entry -> entry.tool },
        toolSearchEngine = ToolSearchEngine(searchDocuments),
    )
}
