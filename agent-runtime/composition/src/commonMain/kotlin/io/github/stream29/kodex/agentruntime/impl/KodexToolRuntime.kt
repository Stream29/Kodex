package io.github.stream29.kodex.agentruntime.impl

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingInvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.contract.ToolName
import io.github.stream29.kodex.utils.logging.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Runtime layer over a static borrowed [tools] catalog.
 */
internal class KodexToolRuntime(
    private val delegate: ResumableAgentLayer,
    tools: List<Tool>,
    private val logger: KLogger,
) : ResumableAgentLayer by delegate {
    private val toolsByName: Map<ToolName, Tool> = ToolRouting.index(tools)

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
        var handledEvent = false
        for (pendingEvent in pending.events) {
            when (pendingEvent) {
                is PendingInvalidToolCall -> {
                    logger
                        .tool(pendingEvent.loggingToolName() ?: "unknown_tool", pendingEvent.callId)
                        .runToolCall {
                            warn { "Tool call input is invalid." }
                            completeToolCall(pendingEvent.completedEvent())
                        }
                    handledEvent = true
                }

                else -> {
                    val toolName = ToolRouting.requireName(pendingEvent)
                    val tool = toolsByName[toolName] ?: continue
                    val toolLogger = logger.tool(toolName.toString(), pendingEvent.callId)
                    toolLogger.runToolCall {
                        handleToolCall(pendingEvent) { tool.handle(pendingEvent) }
                    }
                    handledEvent = true
                }
            }
        }
        return handledEvent && state.value !is KodexAgentStateValue.ToolPending
    }

    private suspend fun KLogger.handleToolCall(
        pending: PendingToolEvent,
        operation: suspend () -> StableCleanEvent.CompletedTool,
    ) {
        var executionFailure: Exception? = null
        val completed = try {
            operation().also { currentCoroutineContext().ensureActive() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            executionFailure = failure
            error(failure) { "Tool execution failed." }
            pending.toFailedToolEvent("Tool execution failed.")
        }
        // State-bound tools may atomically persist their own specialized output.
        val remainsPending = (state.value as? KodexAgentStateValue.ToolPending)
            ?.events
            ?.any { event -> event.callId == pending.callId }
            ?: false
        if (remainsPending) {
            try {
                completeToolCall(completed)
            } catch (failure: Throwable) {
                executionFailure?.let { if (it !== failure) failure.addSuppressed(it) }
                throw failure
            }
        }
    }
}

internal fun ResumableAgentLayer.toolRuntime(
    tools: List<Tool>,
    logger: KLogger,
): KodexToolRuntime = KodexToolRuntime(
    delegate = this,
    tools = tools,
    logger = logger,
)

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
