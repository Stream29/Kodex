package io.github.stream29.codex.lite.agentsession.composition

import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentruntime.plan.planRuntime
import io.github.stream29.codex.lite.agentruntime.tool.toolRuntime
import io.github.stream29.codex.lite.agentruntime.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState

/**
 * Builds the canonical runtime stack for the main Agent.
 *
 * The returned runtime delegates to this state and remains inside its
 * coroutine lifecycle. The owning AgentSession controls that lifecycle.
 */
public fun CodexAgentState.buildMasterAgentRuntime(
    dependencies: CodexAgentDependencies,
): CodexAgentRuntime {
    return compactionRuntime(
        modelCatalog = dependencies.modelCatalog,
        compactionHooks = dependencies.hooks,
    )
        .planRuntime(dependencies.hooks)
        .toolRuntime(
            client = dependencies.client,
            modelCatalog = dependencies.modelCatalog,
            shellSettings = dependencies.shellSettings,
            mcpService = dependencies.mcpService,
            toolHooks = dependencies.hooks,
        )
        .turnHookRuntime(dependencies.hooks)
}

/**
 * Builds the canonical runtime stack for one subagent.
 */
public fun CodexAgentState.buildSubagentRuntime(
    dependencies: CodexAgentDependencies,
): CodexAgentRuntime {
    return compactionRuntime(
        modelCatalog = dependencies.modelCatalog,
        compactionHooks = dependencies.hooks,
    )
        .planRuntime(dependencies.hooks)
        .toolRuntime(
            client = dependencies.client,
            modelCatalog = dependencies.modelCatalog,
            shellSettings = dependencies.shellSettings,
            mcpService = dependencies.mcpService,
            toolHooks = dependencies.hooks,
        )
        .turnHookRuntime(dependencies.hooks)
}
