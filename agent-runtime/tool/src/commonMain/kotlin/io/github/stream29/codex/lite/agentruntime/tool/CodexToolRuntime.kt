package io.github.stream29.codex.lite.agentruntime.tool

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

/** Runtime layer that executes the pending calls owned by [tools]. */
public class CodexToolRuntime(
    private val delegate: CodexAgentRuntime,
    tools: List<Tool>,
) : CodexAgentRuntime by delegate {
    private val toolsByName: Map<ToolName, Tool> = tools.toToolMap()

    override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        while (true) {
            delegate.resume().collect { send(it) }

            val calls = (state.value as? CodexAgentStateValue.ToolPending)
                ?.calls
                .orEmpty()
            val handled = calls.mapNotNull { call ->
                toolsByName[call.toolName]?.let { tool -> call to tool }
            }
            if (handled.isEmpty()) {
                return@channelFlow
            }

            handled.forEach { (call, tool) ->
                completeToolCall(tool.handle(call))
            }
            if (state.value is CodexAgentStateValue.ToolPending) {
                return@channelFlow
            }
        }
    }.buffer(Channel.UNLIMITED)
}

/** Adds handling for [tools]; callers declare their specs through settings. */
public fun CodexAgentRuntime.toolRuntime(tools: List<Tool>): CodexAgentRuntime =
    CodexToolRuntime(this, tools)

private fun List<Tool>.toToolMap(): Map<ToolName, Tool> {
    val routes = flatMap { tool ->
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
        is ToolSpec.WebSearch -> error("CodexToolRuntime only accepts callable local tool specs.")
    }
    require(names.isNotEmpty()) { "A local Tool must expose at least one callable name." }
    return names
}

private val ResponseItem.ToolCall.toolName: ToolName
    get() = when (this) {
        is ResponseItem.FunctionCall -> ToolName(name = name, namespace = namespace)
        is ResponseItem.CustomToolCall -> ToolName(name = name, namespace = namespace)
    }
