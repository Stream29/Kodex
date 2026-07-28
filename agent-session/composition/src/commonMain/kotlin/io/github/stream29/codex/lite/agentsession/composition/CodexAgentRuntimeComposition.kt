package io.github.stream29.codex.lite.agentsession.composition

import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentruntime.tool.toolRuntime
import io.github.stream29.codex.lite.agentruntime.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.tool.contract.Tool
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
): CodexAgentRuntime {
    val fixedTools = fixedTools(dependencies, agentPathResolver)
    val toolSearch = toolSearchState(dependencies.mcpService)
    return fixedTools.closeOnFailure {
        compactionRuntime(
            modelCatalog = dependencies.modelCatalog,
            compactionHooks = dependencies.hooks,
        )
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
