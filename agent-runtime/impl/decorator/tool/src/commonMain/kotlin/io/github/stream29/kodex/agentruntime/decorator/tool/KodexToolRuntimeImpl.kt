package io.github.stream29.kodex.agentruntime.decorator.tool

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolSearchEvent
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.contract.tool.ToolHooks
import io.github.stream29.kodex.hook.toolutils.runPostToolUse
import io.github.stream29.kodex.hook.toolutils.runPreToolUse
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.ToolName
import io.github.stream29.kodex.tool.toolsearch.ToolSearchEngine
import io.github.stream29.kodex.utils.logging.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Default [KodexToolRuntime] over borrowed tool catalogs.
 */
public class KodexToolRuntimeImpl internal constructor(
    private val delegate: ResumableAgentLayer,
    fixedTools: List<Tool>,
    private val dynamicTools: StateFlow<List<Tool>>,
    private val toolSearch: StateFlow<ToolSearchEngine>,
    private val toolHooks: ToolHooks,
    private val logger: KLogger,
) : KodexToolRuntime, KodexAgentState by delegate {
    private val fixedToolsByName: Map<ToolName, Tool> = ToolRouting.index(fixedTools)

    override suspend fun resume() {
        while (true) {
            val pending = state.value as? KodexAgentStateValue.ToolPending
            if (pending == null) {
                delegate.resume()
                val nextPending = state.value as? KodexAgentStateValue.ToolPending ?: return
                if (!handlePendingTools(nextPending)) return
            } else {
                if (!handlePendingTools(pending)) return
            }
        }
    }

    private suspend fun handlePendingTools(pending: KodexAgentStateValue.ToolPending): Boolean {
        val toolsByName = ToolRouting.merge(fixedToolsByName, ToolRouting.index(dynamicTools.value))
        var handledEvent = false
        for (pendingEvent in pending.events) {
            when (pendingEvent) {
                is PendingInvalidToolCall -> {
                    logger
                        .tool(pendingEvent.loggingToolName(), pendingEvent.callId)
                        .runToolCall {
                            warn { "Tool call input is invalid." }
                            completeToolCall(pendingEvent.completedEvent())
                        }
                    handledEvent = true
                }

                is PendingToolSearchEvent -> {
                    logger
                        .tool(pendingEvent.loggingToolName(), pendingEvent.callId)
                        .runToolCall {
                            completeToolCall(toolSearch.value.handle(pendingEvent))
                        }
                    handledEvent = true
                }

                else -> {
                    val toolName = ToolRouting.requireName(pendingEvent)
                    val tool = toolsByName[toolName]
                    if (tool == null && toolName.namespace?.startsWith("mcp__") == true) {
                        logger
                            .tool(pendingEvent.loggingToolName(), pendingEvent.callId)
                            .runToolCall {
                                warn { "MCP tool route is no longer available." }
                                completeToolCall(
                                    pendingEvent.failedEvent(
                                        "The MCP tool is no longer available in the current catalog.",
                                    ),
                                )
                            }
                    } else if (tool == null) {
                        continue
                    } else {
                        val toolLogger = logger.tool(toolName.toString(), pendingEvent.callId)
                        toolLogger.runToolCall {
                            handleToolCall(tool, pendingEvent, toolLogger)
                        }
                    }
                    handledEvent = true
                }
            }
        }
        return handledEvent && state.value !is KodexAgentStateValue.ToolPending
    }

    private suspend fun handleToolCall(
        tool: Tool,
        pending: PendingToolEvent,
        logger: KLogger,
    ) {
        when (val result = toolHooks.runPreToolUse(delegate.storage, pending)) {
            is PreToolUseResult.Block -> {
                logger.warn { "Tool call blocked by PreToolUse hook." }
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

private fun PendingInvalidToolCall.completedEvent(): StableInvalidToolCall =
    StableInvalidToolCall(
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
 *
 * @param logger Agent-scoped logger used to derive each Tool call logger.
 */
public fun ResumableAgentLayer.toolRuntime(
    fixedTools: List<Tool>,
    dynamicTools: StateFlow<List<Tool>>,
    toolSearch: StateFlow<ToolSearchEngine>,
    toolHooks: ToolHooks,
    logger: KLogger,
): KodexToolRuntime =
    KodexToolRuntimeImpl(
        delegate = this,
        fixedTools = fixedTools,
        dynamicTools = dynamicTools,
        toolSearch = toolSearch,
        toolHooks = toolHooks,
        logger = logger,
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

private fun PendingToolEvent.loggingToolName(): String {
    val name = toolName ?: ClientToolSearchName
    return toolNamespace?.let { namespace -> "$namespace.$name" } ?: name
}

private suspend fun <Result> KLogger.runToolCall(
    block: suspend KLogger.() -> Result,
): Result {
    info { "Tool call started." }
    return try {
        block().also {
            info { "Tool call completed." }
        }
    } catch (cancellation: CancellationException) {
        info { "Tool call cancelled." }
        throw cancellation
    } catch (failure: Throwable) {
        error(failure) { "Tool call failed." }
        throw failure
    }
}

private const val ClientToolSearchName: String = "tool_search"
