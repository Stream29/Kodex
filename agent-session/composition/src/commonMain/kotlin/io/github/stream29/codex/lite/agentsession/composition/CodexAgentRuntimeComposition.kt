package io.github.stream29.codex.lite.agentsession.composition

import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentruntime.plan.planRuntime
import io.github.stream29.codex.lite.agentruntime.sessionhook.installSessionHooks
import io.github.stream29.codex.lite.agentruntime.tool.CodexToolRuntime
import io.github.stream29.codex.lite.agentruntime.tool.toolRuntime
import io.github.stream29.codex.lite.agentruntime.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState

/**
 * Builds the canonical runtime stack for the main Agent.
 *
 * The returned runtime delegates to this state and remains inside its
 * coroutine lifecycle. Session ownership is responsible for caching the
 * resulting runtime identity.
 */
public suspend fun CodexAgentState.buildMasterAgentRuntime(
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
        .composeOrClose {
            val runtime = turnHookRuntime(dependencies.hooks)
            runtime.installSessionHooks(dependencies.hooks)
            runtime
        }
}

/**
 * Builds the canonical runtime stack for one subagent.
 *
 * Subagents omit the main Session lifecycle hooks. Their future dedicated
 * lifecycle hooks belong at this composition boundary.
 */
public suspend fun CodexAgentState.buildSubagentRuntime(
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
        .composeOrClose {
            turnHookRuntime(dependencies.hooks)
        }
}

/**
 * Transfers this runtime's ownership to the composed result on success.
 *
 * If composition fails, this runtime is closed before the original failure is
 * rethrown. A close failure is suppressed so it cannot hide the build failure.
 */
private inline fun CodexToolRuntime.composeOrClose(
    compose: CodexToolRuntime.() -> CodexAgentRuntime,
): CodexAgentRuntime =
    try {
        compose()
    } catch (failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
