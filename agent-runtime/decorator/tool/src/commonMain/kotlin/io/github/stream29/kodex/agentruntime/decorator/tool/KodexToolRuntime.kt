package io.github.stream29.kodex.agentruntime.decorator.tool

import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.contract.tool.ToolHooks
import io.github.stream29.kodex.hook.toolutils.runPostToolUse
import io.github.stream29.kodex.hook.toolutils.runPreToolUse
import io.github.stream29.kodex.openai.FreeformTool
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.ToolName
import io.github.stream29.kodex.tool.toolsearch.ToolSearchEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

/**
 * Executes borrowed fixed and dynamic tools plus client tool-search calls.
 *
 * Tool construction, dynamic catalog updates, and resource ownership remain
 * outside this decorator. It only samples the supplied clean pending events,
 * routes them, runs hooks, and persists their clean completions.
 */
public class KodexToolRuntime internal constructor(
    private val delegate: ResumableAgentLayer,
    fixedTools: List<Tool>,
    private val dynamicTools: StateFlow<List<Tool>>,
    private val toolSearch: StateFlow<ToolSearchEngine>,
    private val toolHooks: ToolHooks,
) : ResumableAgentLayer by delegate {
    private val fixedToolsByName: Map<ToolName, Tool> = fixedTools.toToolMap()

    override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        while (true) {
            var pending = state.value as? KodexAgentStateValue.ToolPending
            if (pending == null) {
                delegate.resume().collect { send(it) }
                pending = state.value as? KodexAgentStateValue.ToolPending
                    ?: return@channelFlow
            }
            val toolsByName = fixedToolsByName.merge(dynamicTools.value.toToolMap())
            var handledEvent = false
            for (pendingEvent in pending.events) {
                when (pendingEvent) {
                    is PendingInvalidToolCall -> {
                        completeToolCall(pendingEvent.completedEvent())
                        handledEvent = true
                    }

                    is PendingToolSearchEvent -> {
                        completeToolCall(toolSearch.value.handle(pendingEvent))
                        handledEvent = true
                    }

                    else -> {
                        val toolName = pendingEvent.requireToolName()
                        val tool = toolsByName[toolName]
                        if (tool == null && toolName.namespace?.startsWith("mcp__") == true) {
                            completeToolCall(
                                pendingEvent.failedEvent(
                                    "The MCP tool is no longer available in the current catalog.",
                                ),
                            )
                        } else if (tool == null) {
                            continue
                        } else {
                            handleToolCall(tool, pendingEvent)
                        }
                        handledEvent = true
                    }
                }
            }
            if (!handledEvent) {
                return@channelFlow
            }
            if (state.value is KodexAgentStateValue.ToolPending) {
                return@channelFlow
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun handleToolCall(
        tool: Tool,
        pending: PendingToolEvent,
    ) {
        when (val result = toolHooks.runPreToolUse(delegate.storage, pending)) {
            is PreToolUseResult.Block -> {
                completeToolCall(pending.failedEvent(result.reason))
                return
            }

            PreToolUseResult.Continue -> Unit
        }
        val completed = tool.handle(pending)
        toolHooks.runPostToolUse(
            storage = delegate.storage,
            completed = completed,
        )
        // State-bound tools may atomically persist their own specialized output.
        val remainsPending = (state.value as? KodexAgentStateValue.ToolPending)
            ?.events
            ?.any { event -> event.callId == pending.callId }
            ?: false
        if (remainsPending) {
            completeToolCall(completed)
        }
    }
}

private fun PendingInvalidToolCall.completedEvent(): StableCleanEvent.InvalidToolCall =
    StableCleanEvent.InvalidToolCall(
        callId = callId,
        itemId = itemId,
        invocation = invocation.toStableInvocation(),
        message = message,
    )

private fun PendingInvalidToolInvocation.toStableInvocation(): InvalidToolInvocation =
    when (this) {
        is PendingInvalidToolInvocation.Function ->
            InvalidToolInvocation.Function(
                name = name,
                namespace = namespace,
                arguments = arguments,
            )

        is PendingInvalidToolInvocation.Custom ->
            InvalidToolInvocation.Custom(
                name = name,
                namespace = namespace,
                input = input,
            )

        is PendingInvalidToolInvocation.ToolSearch ->
            InvalidToolInvocation.ToolSearch(arguments)
    }

/**
 * Adds a tool-execution layer over externally owned tool state.
 *
 * This runtime neither creates nor closes any supplied tool or state flow.
 */
public fun ResumableAgentLayer.toolRuntime(
    fixedTools: List<Tool>,
    dynamicTools: StateFlow<List<Tool>>,
    toolSearch: StateFlow<ToolSearchEngine>,
    toolHooks: ToolHooks,
): KodexToolRuntime =
    KodexToolRuntime(
        delegate = this,
        fixedTools = fixedTools,
        dynamicTools = dynamicTools,
        toolSearch = toolSearch,
        toolHooks = toolHooks,
    )

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

private fun Map<ToolName, Tool>.merge(dynamic: Map<ToolName, Tool>): Map<ToolName, Tool> {
    val duplicateNames = keys intersect dynamic.keys
    require(duplicateNames.isEmpty()) {
        "Fixed and dynamic tools handle the same name: ${duplicateNames.joinToString()}"
    }
    return this + dynamic
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
        is ToolSpec.WebSearch -> error("KodexToolRuntime only accepts callable local tool specs.")
    }
    require(names.isNotEmpty()) { "A local Tool must expose at least one callable name." }
    return names
}

private fun PendingToolEvent.requireToolName(): ToolName =
    ToolName(
        name = requireNotNull(toolName) {
            "Pending event ${this::class.simpleName} does not identify a local tool route."
        },
        namespace = toolNamespace,
    )

private fun ToolSearchEngine.handle(
    pending: PendingToolSearchEvent,
): StableToolSearchEvent =
    StableToolSearchEvent(
        callId = pending.callId,
        itemId = pending.itemId,
        arguments = pending.arguments,
        result = search(pending.arguments),
    )

private fun PendingToolEvent.failedEvent(message: String): StableCleanEvent.CompletedTool =
    toFailedToolEvent(message)
