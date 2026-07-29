package io.github.stream29.codex.lite.agentsession.composition

import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.composite.CompositeAgentRuntime
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentruntime.steer.steerRuntime
import io.github.stream29.codex.lite.agentruntime.tool.toolRuntime
import io.github.stream29.codex.lite.agentruntime.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.coroutines.flow.MutableStateFlow
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
): CompositeAgentRuntime {
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
        CompositeAgentRuntimeImpl(runtime, pendingSteer)
    }
}

private class CompositeAgentRuntimeImpl(
    delegate: CodexAgentRuntime,
    override val pendingSteer: MutableStateFlow<List<ContentItem>>,
) : CompositeAgentRuntime, CodexAgentRuntime by delegate

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
