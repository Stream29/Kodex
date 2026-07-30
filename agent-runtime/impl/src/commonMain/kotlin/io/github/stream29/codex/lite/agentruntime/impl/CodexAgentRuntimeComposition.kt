package io.github.stream29.codex.lite.agentruntime.impl

import io.github.stream29.codex.lite.agentruntime.decorator.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.contract.AgentRuntime
import io.github.stream29.codex.lite.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentruntime.decorator.steer.steerRuntime
import io.github.stream29.codex.lite.agentruntime.decorator.subagent.subagentParentNotificationRuntime
import io.github.stream29.codex.lite.agentruntime.decorator.tool.toolRuntime
import io.github.stream29.codex.lite.agentruntime.decorator.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentDependencies
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecToolClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job

/** Builds the root Agent runtime. */
public fun CodexAgentState.buildMasterAgentRuntime(
    dependencies: CodexAgentDependencies,
    agentPathResolver: AgentPathResolver,
): AgentRuntime =
    buildAgentRuntime { pendingSteer ->
        masterRuntimeLayer(dependencies, agentPathResolver, pendingSteer)
    }

private fun CodexAgentState.masterRuntimeLayer(
    dependencies: CodexAgentDependencies,
    agentPathResolver: AgentPathResolver,
    pendingSteer: MutableStateFlow<List<StableCleanEvent.Steerable>>,
): AgentRuntimeLayer {
    val toolSearch = toolSearchState(dependencies.mcpService)
    val fixedTools = fixedTools(dependencies, agentPathResolver, pendingSteer)
    return fixedTools.tools.closeOnFailure {
        AgentRuntimeLayer(
            delegate = compactionRuntime(
                modelCatalog = dependencies.modelCatalog,
                compactionHooks = dependencies.hooks,
            )
                .steerRuntime {
                    pendingSteer.getAndUpdate { emptyList() }
                }
                .toolRuntime(
                    fixedTools = fixedTools.tools,
                    dynamicTools = dependencies.mcpService.tools,
                    toolSearch = toolSearch,
                    toolHooks = dependencies.hooks,
                )
                .turnHookRuntime(dependencies.hooks)
                .also {
                    coroutineContext.job.invokeOnCompletion {
                        fixedTools.tools.closeAll()
                    }
                },
            unifiedExecToolClient = fixedTools.unifiedExecToolClient,
        )
    }
}

/** Builds a spawned Agent runtime. */
public fun CodexAgentState.buildSubagentRuntime(
    dependencies: CodexAgentDependencies,
    agentPathResolver: AgentPathResolver,
): AgentRuntime =
    buildAgentRuntime { pendingSteer ->
        subagentRuntimeLayer(dependencies, agentPathResolver, pendingSteer)
    }

private fun CodexAgentState.subagentRuntimeLayer(
    dependencies: CodexAgentDependencies,
    agentPathResolver: AgentPathResolver,
    pendingSteer: MutableStateFlow<List<StableCleanEvent.Steerable>>,
): AgentRuntimeLayer {
    val toolSearch = toolSearchState(dependencies.mcpService)
    val fixedTools = fixedTools(dependencies, agentPathResolver, pendingSteer)
    return fixedTools.tools.closeOnFailure {
        AgentRuntimeLayer(
            delegate = compactionRuntime(
                modelCatalog = dependencies.modelCatalog,
                compactionHooks = dependencies.hooks,
            )
                .steerRuntime {
                    pendingSteer.getAndUpdate { emptyList() }
                }
                .toolRuntime(
                    fixedTools = fixedTools.tools,
                    dynamicTools = dependencies.mcpService.tools,
                    toolSearch = toolSearch,
                    toolHooks = dependencies.hooks,
                )
                .turnHookRuntime(dependencies.hooks)
                .also {
                    coroutineContext.job.invokeOnCompletion {
                        fixedTools.tools.closeAll()
                    }
                }
                .subagentParentNotificationRuntime { message ->
                    agentPathResolver.resolveOrNull(message.recipient)?.let { parent ->
                        parent.runtime.pendingSteer.update { pending ->
                            pending + message
                        }
                    }
                },
            unifiedExecToolClient = fixedTools.unifiedExecToolClient,
        )
    }
}

private fun CodexAgentState.buildAgentRuntime(
    buildLayer: (MutableStateFlow<List<StableCleanEvent.Steerable>>) -> AgentRuntimeLayer,
): AgentRuntime {
    val pendingSteer = MutableStateFlow(emptyList<StableCleanEvent.Steerable>())
    val layer = buildLayer(pendingSteer)
    return AgentRuntimeImpl(
        delegate = layer.delegate,
        pendingSteer = pendingSteer,
        unifiedExecToolClient = layer.unifiedExecToolClient,
    )
}

private data class AgentRuntimeLayer(
    val delegate: ResumableAgentLayer,
    val unifiedExecToolClient: UnifiedExecToolClient,
)

private class AgentRuntimeImpl(
    private val delegate: ResumableAgentLayer,
    override val pendingSteer: MutableStateFlow<List<StableCleanEvent.Steerable>>,
    override val unifiedExecToolClient: UnifiedExecToolClient,
) : AgentRuntime, ResumableAgentLayer by delegate {
    private val runningTurnSlot: MutableStateFlow<Job?> = MutableStateFlow(null)

    override val runningTurn: StateFlow<Job?> = runningTurnSlot.asStateFlow()

    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        val turn = currentCoroutineContext().job
        if (!runningTurnSlot.compareAndSet(null, turn)) {
            throw ConcurrentAgentRuntimeResumeException()
        }
        try {
            emitAll(delegate.resume())
        } finally {
            runningTurnSlot.compareAndSet(turn, null)
        }
    }
}

private fun List<Tool>.closeAll() {
    asReversed().forEach { tool ->
        runCatching { tool.close() }
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
