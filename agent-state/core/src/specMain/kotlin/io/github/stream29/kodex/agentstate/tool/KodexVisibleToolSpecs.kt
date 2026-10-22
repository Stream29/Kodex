package io.github.stream29.kodex.agentstate.tool

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.toolsearch.ToolSearchDocument

/**
 * Returns the tool definitions visible in one Responses API request.
 *
 * MCP tools and local deferred tools remain behind ToolSearch. The ToolSearch
 * source listing is derived from the exact indexed document snapshot.
 */
public expect fun McpService.visibleToolSpecs(settings: KodexAgentSettings): List<ToolSpec>

/** Builds the deferred search directory used by both request and runtime paths. */
public expect fun List<McpTool>.toDeferredToolSearchDocuments(): List<ToolSearchDocument>

/** Converts one provider-issued local tool call at the AgentState boundary. */
public expect fun ResponseItem.ToolCall.toPendingToolEvent(): PendingToolEvent
