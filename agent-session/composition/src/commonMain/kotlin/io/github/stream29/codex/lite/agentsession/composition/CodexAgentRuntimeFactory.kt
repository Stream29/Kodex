package io.github.stream29.codex.lite.agentsession.composition

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState as createCodexAgentState

/** Creates the runtime owned by a user-visible root AgentSession. */
public suspend fun CoroutineScope.createMasterAgentRuntime(
    storage: MutableCodexAgentStorage,
    dependencies: CodexAgentDependencies,
): CodexAgentRuntime =
    createAgentRuntime(storage, dependencies) {
        buildMasterAgentRuntime(dependencies)
    }

/** Creates the runtime owned by a non-root subagent session. */
public suspend fun CoroutineScope.createSubagentRuntime(
    storage: MutableCodexAgentStorage,
    dependencies: CodexAgentDependencies,
): CodexAgentRuntime =
    createAgentRuntime(storage, dependencies) {
        buildSubagentRuntime(dependencies)
    }

private suspend inline fun CoroutineScope.createAgentRuntime(
    storage: MutableCodexAgentStorage,
    dependencies: CodexAgentDependencies,
    build: CodexAgentState.() -> CodexAgentRuntime,
): CodexAgentRuntime {
    val state = createCodexAgentState(
        client = dependencies.client,
        storage = storage,
        contextSettings = dependencies.contextSettings,
        mcpService = dependencies.mcpService,
    )
    return try {
        state.build()
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            state.coroutineContext.job.cancelAndJoin()
        }
        throw failure
    }
}
