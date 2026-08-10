package io.github.stream29.kodex.agentstate.tool

import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.currenttime.CurrentTimeTools
import io.github.stream29.kodex.tool.getcontextremaining.GetContextRemainingTools
import io.github.stream29.kodex.tool.imagegeneration.ImageGenerationTools
import io.github.stream29.kodex.tool.multiagent.MultiAgentTools
import io.github.stream29.kodex.tool.plan.PlanTools
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.kodex.tool.toolsearch.ToolSearchDocument
import io.github.stream29.kodex.tool.toolsearch.ToolSearchSourceInfo
import io.github.stream29.kodex.tool.toolsearch.ToolSearchTools
import io.github.stream29.kodex.tool.toolsearch.toToolSearchDocuments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.tool.viewimage.ViewImageTools
import io.github.stream29.kodex.tool.webrun.WebRunTools

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
    settings: KodexAgentSettings,
): List<ToolSpec> {
    val deferredDocuments = clients.value.values
        .flatMap(McpClient::listTools)
        .toDeferredToolSearchDocuments()
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
