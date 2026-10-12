package io.github.stream29.kodex.agentruntime.decorator.tool

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.FreeformTool
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.ToolName

/**
 * Indexes callable local tool names for this implementation.
 *
 * The index expands namespace members and checks collisions before execution.
 */
internal object ToolRouting {
    /** Indexes callable routes, rejecting duplicate names within one catalog. */
    public fun index(tools: List<Tool>): Map<ToolName, Tool> {
        val routes = tools.flatMap { tool ->
            tool.routingNames().map { toolName -> toolName to tool }
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

    /** Merges fixed and dynamic catalogs, rejecting cross-catalog collisions. */
    public fun merge(fixed: Map<ToolName, Tool>, dynamic: Map<ToolName, Tool>): Map<ToolName, Tool> {
        val duplicateNames = fixed.keys intersect dynamic.keys
        require(duplicateNames.isEmpty()) {
            "Fixed and dynamic tools handle the same name: ${duplicateNames.joinToString()}"
        }
        return fixed + dynamic
    }

    /** Requires a local route name from a pending event. */
    public fun requireName(pending: PendingToolEvent): ToolName =
        ToolName(
            name = requireNotNull(pending.toolName) {
                "Pending event ${pending::class.simpleName} does not identify a local tool route."
            },
            namespace = pending.toolNamespace,
        )

    private fun Tool.routingNames(): List<ToolName> {
        val names = when (val spec = spec) {
            is ResponsesApiTool -> listOf(ToolName.plain(spec.name))
            is FreeformTool -> listOf(ToolName.plain(spec.name))
            is ResponsesApiNamespace -> spec.tools.map { namespaceTool ->
                when (namespaceTool) {
                    is ResponsesApiTool -> ToolName.namespaced(spec.name, namespaceTool.name)
                }
            }

            is ToolSpec.ImageGeneration,
            is ToolSpec.ToolSearch,
            is ToolSpec.WebSearch -> error("KodexToolRuntime only accepts callable local tool specs.")
        }
        require(names.isNotEmpty()) { "A local Tool must expose at least one callable name." }
        return names
    }
}
