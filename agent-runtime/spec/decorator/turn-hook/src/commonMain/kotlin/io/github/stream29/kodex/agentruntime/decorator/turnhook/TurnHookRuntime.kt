package io.github.stream29.kodex.agentruntime.decorator.turnhook

import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer

/**
 * Outer runtime layer for user-prompt and Stop Hook control flow.
 *
 * Each outer [resume] uses the persisted settings snapshot for its Hook turn
 * identity rather than coroutine-local state. Additional user-prompt context
 * and Stop continuation fragments are injected as clean history events.
 */
public interface TurnHookRuntime : ResumableAgentLayer {
    /**
     * Runs UserPromptSubmit before the inner resume when a persisted user
     * message starts this call. Additional context is persisted as developer
     * messages; a user-prompt Stop returns without delegating further.
     *
     * After an inner resume, Stop runs for an assistant message or pending
     * host-owned interaction. Finish returns without changing pending calls;
     * explicit Stop fails those interactions and returns. Continue with no
     * fragments also returns without changing them. Continue with fragments
     * fails pending host interactions, injects one user message with one
     * `hook_prompt` XML text item per fragment, and resumes the inner layer
     * in the same turn.
     * Subsequent Stop calls report that continuation is already active.
     */
    public override suspend fun resume()
}
