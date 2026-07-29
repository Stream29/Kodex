package io.github.stream29.codex.lite.agentruntime.impl

import io.github.stream29.codex.lite.agentruntime.decorator.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.contract.AgentRuntime
import io.github.stream29.codex.lite.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentruntime.decorator.steer.steerRuntime
import io.github.stream29.codex.lite.agentruntime.decorator.tool.toolRuntime
import io.github.stream29.codex.lite.agentruntime.decorator.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentDependencies
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.job

/**
 * Builds the canonical runtime stack for one Agent.
 *
 * The returned runtime delegates to this state and remains inside its
 * coroutine lifecycle. The owning AgentSession controls that lifecycle.
 */
public fun CodexAgentState.buildAgentRuntime(
    dependencies: CodexAgentDependencies,
    agentPathResolver: AgentPathResolver,
): AgentRuntime {
    val fixedTools = fixedTools(dependencies, agentPathResolver)
    val toolSearch = toolSearchState(dependencies.mcpService)
    val pendingSteer = MutableStateFlow(emptyList<ContentItem>())
    return fixedTools.closeOnFailure {
        val runtime = compactionRuntime(
            modelCatalog = dependencies.modelCatalog,
            compactionHooks = dependencies.hooks,
        )
            .steerRuntime {
                pendingSteer.getAndUpdate { emptyList() }
            }
            .toolRuntime(
                fixedTools = fixedTools,
                dynamicTools = dependencies.mcpService.tools,
                toolSearch = toolSearch,
                toolHooks = dependencies.hooks,
            )
            .turnHookRuntime(dependencies.hooks)
            .also {
                coroutineContext.job.invokeOnCompletion {
                    fixedTools.closeAll()
                }
            }
        AgentRuntimeImpl(runtime, pendingSteer)
    }
}

private class AgentRuntimeImpl(
    private val delegate: ResumableAgentLayer,
    override val pendingSteer: MutableStateFlow<List<ContentItem>>,
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
