package io.github.stream29.kodex.agentruntime.impl

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall
import io.github.stream29.kodex.openai.FreeformTool
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.ToolName

/**
 * Indexes callable plain local tool names and checks collisions before execution.
 */
internal object ToolRouting {
    /** Indexes callable routes, rejecting duplicate names within one catalog. */
    fun index(tools: List<Tool>): Map<ToolName, Tool> {
        val routes = tools.map { tool ->
            tool.routingName() to tool
        }
        val duplicateNames = routes
            .groupBy(keySelector = { (toolName) -> toolName })
            .filterValues { routesForName -> routesForName.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Multiple tools handle the same name: ${duplicateNames.joinToString()}"
        }
        return routes.toMap()
    }

    /** Requires a local route name from a pending event, preserving any model-supplied namespace. */
    fun requireName(pending: PendingToolCall): ToolName =
        ToolName(
            name = requireNotNull(pending.toolName) {
                "Pending event ${pending::class.simpleName} does not identify a local tool route."
            },
            namespace = pending.toolNamespace,
        )

    private fun Tool.routingName(): ToolName =
        when (val spec = spec) {
            is ResponsesApiTool -> ToolName.plain(spec.name)
            is FreeformTool -> ToolName.plain(spec.name)
            is ResponsesApiNamespace -> error("KodexToolRuntime only accepts callable local tool specs.")
        }
}
