package io.github.stream29.kodex.agentruntime.impl

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.decorator.compact.compactionRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentruntime.decorator.steer.steerRuntime
import io.github.stream29.kodex.agentruntime.decorator.tool.toolRuntime
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.kodex.utils.logging.agent
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.logging.session
import io.github.stream29.kodex.utils.logging.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Builds the root Agent runtime. */
public fun KodexAgentState.buildMasterAgentRuntime(
    dependencies: KodexAgentDependencies,
): AgentRuntime {
    val sessionLogger = RuntimeLogger.session(storage.uri)
    return try {
        buildAgentRuntime(sessionLogger) { pendingSteer, agentLogger ->
            masterRuntimeLayer(
                dependencies = dependencies,
                pendingSteer = pendingSteer,
                logger = agentLogger,
            )
        }.also {
            sessionLogger.info { "Session opened." }
            coroutineContext.job.invokeOnCompletion { failure ->
                sessionLogger.logClosed(
                    failure = failure,
                    closedMessage = "Session closed.",
                    failedMessage = "Session closed after a failure.",
                )
            }
        }
    } catch (failure: Throwable) {
        sessionLogger.error(failure) { "Session failed to open." }
        throw failure
    }
}

private fun KodexAgentState.masterRuntimeLayer(
    dependencies: KodexAgentDependencies,
    pendingSteer: MutableStateFlow<List<StableIndexEvent.Steerable>>,
    logger: KLogger,
): AgentRuntimeLayer {
    val mcpTools = mcpToolsState(dependencies.mcpService)
    val toolSearch = toolSearchState(mcpTools)
    val fixedTools = fixedTools(dependencies)
    return fixedTools.tools.closeOnFailure {
        AgentRuntimeLayer(
            delegate = compactionRuntime(
                modelCatalog = dependencies.modelCatalog,
                logger = logger,
            )
                .steerRuntime(logger = logger) {
                    pendingSteer.getAndUpdate { emptyList() }
                }
                .toolRuntime(
                    fixedTools = fixedTools.tools,
                    dynamicTools = mcpTools,
                    toolSearch = toolSearch,
                    logger = logger,
                )
                .also {
                    coroutineContext.job.invokeOnCompletion {
                        fixedTools.tools.closeAll(logger)
                    }
                },
            unifiedExecToolClient = fixedTools.unifiedExecToolClient,
        )
    }
}

private fun KodexAgentState.buildAgentRuntime(
    sessionLogger: KLogger,
    buildLayer: (
        MutableStateFlow<List<StableIndexEvent.Steerable>>,
        KLogger,
    ) -> AgentRuntimeLayer,
): AgentRuntime {
    val logger = sessionLogger.agent()
    val pendingSteer = MutableStateFlow(emptyList<StableIndexEvent.Steerable>())
    return try {
        val layer = buildLayer(pendingSteer, logger)
        logger.info { "Agent runtime opened." }
        coroutineContext.job.invokeOnCompletion { failure ->
            logger.logClosed(
                failure = failure,
                closedMessage = "Agent runtime closed.",
                failedMessage = "Agent runtime closed after a failure.",
            )
        }
        AgentRuntimeImpl(
            delegate = layer.delegate,
            pendingSteer = pendingSteer,
            unifiedExecToolClient = layer.unifiedExecToolClient,
            logger = logger,
        )
    } catch (failure: Throwable) {
        logger.error(failure) { "Agent runtime failed to open." }
        throw failure
    }
}

private data class AgentRuntimeLayer(
    val delegate: ResumableAgentLayer,
    val unifiedExecToolClient: UnifiedExecToolClient,
)

private class AgentRuntimeImpl(
    private val delegate: ResumableAgentLayer,
    override val pendingSteer: MutableStateFlow<List<StableIndexEvent.Steerable>>,
    override val unifiedExecToolClient: UnifiedExecToolClient,
    private val logger: KLogger,
) : AgentRuntime, KodexAgentState by delegate {
    private val runningTurnSlot: MutableStateFlow<Job?> = MutableStateFlow(null)

    override val runningTurn: StateFlow<Job?> = runningTurnSlot.asStateFlow()

    override suspend fun resume(): Unit = withRunningTurn {
        logger.info { "Agent turn started." }
        try {
            delegate.resume()
            logger.logPendingHostToolCalls(delegate.state.value)
            logger.info { "Agent turn completed." }
        } catch (cancellation: CancellationException) {
            val cleanupFailure = withContext(NonCancellable) {
                runCatching { delegate.clearPending() }.exceptionOrNull()
            }
            if (cleanupFailure != null && cleanupFailure !== cancellation) {
                cancellation.addSuppressed(cleanupFailure)
            }
            logger.info { "Agent turn cancelled." }
            throw cancellation
        } catch (failure: Throwable) {
            logger.error(failure) { "Agent turn failed." }
            throw failure
        }
    }

    override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int = withRunningTurn {
        delegate.compact(trigger, reason, phase)
    }

    private suspend fun <T> withRunningTurn(block: suspend () -> T): T {
        val turn = currentCoroutineContext().job
        if (!runningTurnSlot.compareAndSet(null, turn)) {
            logger.warn { "Rejected concurrent Agent execution." }
            throw ConcurrentAgentRuntimeResumeException()
        }
        try {
            return block()
        } finally {
            runningTurnSlot.compareAndSet(turn, null)
        }
    }

    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int {
        val pending = delegate.pendingToolFor(completed)
            ?: return delegate.completeToolCall(completed)
        val toolName = pending.loggingToolNameOrNull()
            ?: return delegate.completeToolCall(completed)
        val toolLogger = logger.tool(toolName, pending.callId)
        return try {
            delegate.completeToolCall(completed).also {
                toolLogger.info { "Tool call completed by host." }
            }
        } catch (cancellation: CancellationException) {
            toolLogger.info { "Host tool call completion cancelled." }
            throw cancellation
        } catch (failure: Throwable) {
            toolLogger.error(failure) { "Host tool call completion failed." }
            throw failure
        }
    }
}

private fun List<Tool>.closeAll(logger: KLogger) {
    asReversed().forEach { tool ->
        try {
            tool.close()
        } catch (failure: Throwable) {
            logger.warn(failure) { "Failed to close Agent tool." }
        }
    }
}

private inline fun <Result> List<Tool>.closeOnFailure(
    block: () -> Result,
): Result =
    try {
        block()
    } catch (failure: Throwable) {
        asReversed().forEach { tool ->
            try {
                tool.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
        }
        throw failure
    }

private fun KLogger.logClosed(
    failure: Throwable?,
    closedMessage: String,
    failedMessage: String,
) {
    if (failure == null || failure is CancellationException) {
        info { closedMessage }
    } else {
        error(failure) { failedMessage }
    }
}

private fun KLogger.logPendingHostToolCalls(state: KodexAgentStateValue) {
    val pending = state as? KodexAgentStateValue.ToolPending ?: return
    pending.events.forEach { event ->
        event.loggingToolNameOrNull()?.let { toolName ->
            tool(toolName, event.callId).info {
                "Tool call is awaiting host completion."
            }
        }
    }
}

private fun ResumableAgentLayer.pendingToolFor(
    completed: StableCleanEvent.CompletedTool,
): PendingToolEvent? {
    val callId = completed.toResponseHistoryItems()
        .filterIsInstance<ResponseItem.ToolCallOutput>()
        .singleOrNull()
        ?.callId
        ?: return null
    return (state.value as? KodexAgentStateValue.ToolPending)
        ?.events
        ?.firstOrNull { pending -> pending.callId == callId }
}

private fun PendingToolEvent.loggingToolNameOrNull(): String? {
    val name = toolName ?: return null
    return toolNamespace?.let { namespace -> "$namespace.$name" } ?: name
}

private val RuntimeLogger: KLogger by lazy {
    KotlinLogging.logger {}.global()
}
