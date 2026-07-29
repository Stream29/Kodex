package io.github.stream29.codex.lite.agentruntime.decorator.tool

import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.hook.toolutils.runPreToolUse
import io.github.stream29.codex.lite.hook.toolutils.runPostToolUse
import io.github.stream29.codex.lite.hook.toolutils.toHookBlockedOutput
import io.github.stream29.codex.lite.openai.FreeformTool
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.contract.ToolName
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchEngine
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Executes borrowed fixed and dynamic tools plus client tool-search calls.
 *
 * Tool construction, dynamic catalog updates, and resource ownership remain
 * outside this decorator. It only samples the supplied state, routes calls,
 * runs hooks, and persists outputs.
 */
public class CodexToolRuntime internal constructor(
    private val delegate: ResumableAgentLayer,
    fixedTools: List<Tool>,
    private val dynamicTools: StateFlow<List<Tool>>,
    private val toolSearch: StateFlow<ToolSearchEngine>,
    private val toolHooks: ToolHooks,
) : ResumableAgentLayer by delegate {
    private val fixedToolsByName: Map<ToolName, Tool> = fixedTools.toToolMap()

    override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        while (true) {
            var pending = state.value as? CodexAgentStateValue.ToolPending
            if (pending == null) {
                delegate.resume().collect { send(it) }
                pending = state.value as? CodexAgentStateValue.ToolPending
                    ?: return@channelFlow
            }
            val toolsByName = fixedToolsByName.merge(dynamicTools.value.toToolMap())
            var handledCall = false
            for (call in pending.calls) {
                when (call) {
                    is ResponseItem.ClientToolSearchCall -> {
                        completeToolCall(toolSearch.value.handle(call))
                        handledCall = true
                    }

                    is ResponseItem.FunctionCall,
                    is ResponseItem.CustomToolCall,
                    -> {
                        val tool = toolsByName[call.toolName]
                        if (tool == null && call.toolName.namespace?.startsWith("mcp__") == true) {
                            completeToolCall(
                                call.unavailable(
                                    "The MCP tool is no longer available in the current catalog.",
                                ),
                            )
                        } else if (tool == null) {
                            continue
                        } else {
                            handleToolCall(tool, call)
                        }
                        handledCall = true
                    }
                }
            }
            if (!handledCall) {
                return@channelFlow
            }
            if (state.value is CodexAgentStateValue.ToolPending) {
                return@channelFlow
            }
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun handleToolCall(
        tool: Tool,
        call: ResponseItem.ToolCall,
    ) {
        when (val result = toolHooks.runPreToolUse(delegate.storage, call)) {
            is PreToolUseResult.Block -> {
                completeToolCall(call.toHookBlockedOutput(result.reason))
                return
            }

            PreToolUseResult.Continue -> Unit
        }
        val output = tool.handle(call)
        toolHooks.runPostToolUse(
            storage = delegate.storage,
            call = call,
            output = output,
        )
        // State-bound tools may atomically persist their own specialized output.
        val remainsPending = (state.value as? CodexAgentStateValue.ToolPending)
            ?.calls
            ?.any { pendingCall -> pendingCall.callId == call.callId }
            ?: false
        if (remainsPending) {
            completeToolCall(output)
        }
    }
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
): CodexToolRuntime =
    CodexToolRuntime(
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
        is ToolSpec.WebSearch -> error("CodexToolRuntime only accepts callable local tool specs.")
    }
    require(names.isNotEmpty()) { "A local Tool must expose at least one callable name." }
    return names
}

private val ResponseItem.ToolCall.toolName: ToolName
    get() = when (this) {
        is ResponseItem.FunctionCall -> ToolName(name = name, namespace = namespace)
        is ResponseItem.CustomToolCall -> ToolName(name = name, namespace = namespace)
        is ResponseItem.ClientToolSearchCall -> error("Client tool search calls have no tool name.")
    }

private fun ToolSearchEngine.handle(
    call: ResponseItem.ClientToolSearchCall,
): ResponseItem.ClientToolSearchOutput {
    val result = try {
        val arguments = OpenAiJsonCodec.decodeFromJsonElement<SearchToolCallParams>(call.arguments)
        search(arguments)
    } catch (_: SerializationException) {
        null
    }
    return ResponseItem.ClientToolSearchOutput(
        callId = call.callId,
        status = "completed",
        tools = (result as? ToolSearchResult.Success)?.tools.orEmpty(),
    )
}

private fun ResponseItem.ToolCall.unavailable(message: String): ResponseItem.ToolCallOutput =
    when (this) {
        is ResponseItem.FunctionCall -> ResponseItem.FunctionCallOutput(
            callId = callId,
            output = FunctionCallOutputPayload.fromText(message).copy(success = false),
        )

        is ResponseItem.CustomToolCall -> ResponseItem.CustomToolCallOutput(
            callId = callId,
            output = FunctionCallOutputPayload.fromText(message).copy(success = false),
        )

        is ResponseItem.ClientToolSearchCall -> error("Client tool-search calls have a dedicated output type.")
    }
