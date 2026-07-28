package io.github.stream29.codex.lite.agentstate.tool

import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.mcp.contract.McpTool
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.currenttime.CurrentTimeTools
import io.github.stream29.codex.lite.tool.getcontextremaining.GetContextRemainingTools
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenerationTools
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.plan.PlanTools
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchDocument
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchSourceInfo
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.tool.toolsearch.toToolSearchDocuments
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.tool.viewimage.ViewImageTools
import io.github.stream29.codex.lite.tool.webrun.WebRunTools

private val DirectToolSpecs: List<ToolSpec> = buildList {
    add(ApplyPatchTools.spec)
    add(CurrentTimeTools.spec)
    add(GetContextRemainingTools.spec)
    add(UnifiedExecTools.execCommandSpec)
    add(UnifiedExecTools.writeStdinSpec)
    add(WebRunTools.spec)
    addAll(MultiAgentTools.specs)
}

private val LocalDeferredToolSearchDocuments: List<ToolSearchDocument> =
    listOf(ViewImageTools.spec, ImageGenerationTools.spec)
        .flatMap { spec -> spec.toToolSearchDocuments() }

/**
 * Returns the tool definitions visible in one Responses API request.
 *
 * MCP tools and local deferred tools remain behind ToolSearch. The ToolSearch
 * source listing is derived from the exact indexed document snapshot.
 */
public fun McpService.visibleToolSpecs(
    settings: CodexAgentSettings,
): List<ToolSpec> {
    val deferredDocuments = tools.value.toDeferredToolSearchDocuments()
    return buildList {
        addAll(DirectToolSpecs)
        if (settings.collaborationMode == ModeKind.Default) {
            add(PlanTools.spec)
        }
        add(RequestUserInputTools.spec)
        if (deferredDocuments.isNotEmpty()) {
            add(
                ToolSearchTools.createToolSearchSpec(
                    searchableSources = deferredDocuments.mapNotNull(ToolSearchDocument::sourceInfo),
                ),
            )
        }
    }
}

/** Builds the deferred search directory used by both request and runtime paths. */
public fun List<McpTool>.toDeferredToolSearchDocuments(): List<ToolSearchDocument> =
    LocalDeferredToolSearchDocuments + flatMap { tool ->
        tool.spec.toToolSearchDocuments(
            sourceInfo = ToolSearchSourceInfo(
                name = tool.serverName,
                description = tool.serverInstructions.ifBlank { null },
            ),
        )
    }
