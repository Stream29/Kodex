package io.github.stream29.kodex.agentruntime.decorator.tool

import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue

/**
 * [ResumableAgentLayer] that handles pending local tool and client tool-search calls.
 *
 * The supplied fixed tools, dynamic tool catalog, tool-search engine, and their
 * state flows are borrowed: this layer neither constructs nor closes them.
 * Fixed callable routes are established when the layer is composed; dynamic
 * routes are sampled when a [KodexAgentStateValue.ToolPending] boundary is
 * handled. Function, freeform, and namespace-member routes must be callable
 * and unique within and across the fixed and dynamic catalogs.
 */
public interface KodexToolRuntime : ResumableAgentLayer {
    /**
     * Handles an existing pending-tool boundary before delegating to the inner
     * layer. Otherwise delegates first and handles any tool calls it leaves
     * pending. Invalid invocations are persisted as invalid completions, and
     * client tool-search calls use the current search engine.
     *
     * A routed local tool invokes its handler directly, without a script control
     * route. Its clean completion is persisted only if the
     * call is still pending, since state-bound tools may have completed it
     * atomically themselves. A missing `mcp__` route is completed as a failure;
     * an unowned non-MCP route remains pending.
     *
     * When handling advances state out of ToolPending, the inner layer resumes.
     * If calls remain unhandled, this call returns at the observable state
     * boundary. Uncaught handler failures propagate rather than
     * becoming completions.
     *
     * @throws IllegalArgumentException if the sampled dynamic catalog has
     * duplicate, empty, or fixed-colliding routes, or a pending local call
     * does not identify a route name.
     */
    public override suspend fun resume()
}
