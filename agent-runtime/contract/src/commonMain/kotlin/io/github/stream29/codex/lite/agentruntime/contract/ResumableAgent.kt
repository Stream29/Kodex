package io.github.stream29.codex.lite.agentruntime.contract

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * An [CodexAgentState] that can resume multi-step agent execution.
 *
 * Implementations may compose through Kotlin interface delegation to add tool
 * execution, hooks, skills, AGENTS.md support, or temporary context injection.
 * The inherited operations remain the single-step atomic API; [resume] is the
 * multi-step orchestration entry point.
 */
public interface ResumableAgent : CodexAgentState {
    /**
     * Executes this layer's resume operation and exposes raw stream events.
     *
     * Each layer may perform work before or after delegating to the next one.
     * This call is the composable execution boundary; no separate turn runner
     * or admission callback participates in control flow.
     */
    public fun resume(): Flow<ResponsesStreamEvent>
}
