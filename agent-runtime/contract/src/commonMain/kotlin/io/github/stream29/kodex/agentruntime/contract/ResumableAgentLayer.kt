package io.github.stream29.kodex.agentruntime.contract

import io.github.stream29.kodex.agentstate.contract.KodexAgentState

/**
 * An [KodexAgentState] that can resume multi-step agent execution.
 *
 * Implementations may compose through Kotlin interface delegation to add tool
 * execution, hooks, skills, AGENTS.md support, or temporary context injection.
 * The inherited operations remain the single-step atomic API; [resume] is the
 * multi-step orchestration entry point.
 */
public interface ResumableAgentLayer : KodexAgentState {
    /**
     * Executes this layer's resume operation.
     *
     * Each layer may perform work before or after delegating to the next one.
     * This call is the composable execution boundary; no separate turn runner
     * or admission callback participates in control flow. Callers read the
     * observable AgentState boundary rather than receiving a duplicated reason.
     */
    public suspend fun resume()
}
