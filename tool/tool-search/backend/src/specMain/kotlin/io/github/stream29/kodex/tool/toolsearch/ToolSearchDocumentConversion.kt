package io.github.stream29.kodex.tool.toolsearch

import io.github.stream29.kodex.openai.ToolSpec

/**
 * @param sourceInfo Nullable because the generated documents may have no source
 * label for the tool_search description; `null` means no source label.
 */
public expect fun ToolSpec.toToolSearchDocuments(
    sourceInfo: ToolSearchSourceInfo? = null,
): List<ToolSearchDocument>
