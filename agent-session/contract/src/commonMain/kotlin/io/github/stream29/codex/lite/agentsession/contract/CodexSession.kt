package io.github.stream29.codex.lite.agentsession.contract

import io.github.stream29.codex.lite.agentruntime.contract.AgentRuntime
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import kotlinx.coroutines.CoroutineScope

/**
 * One exclusively owned Agent node in a recursive Codex session tree.
 *
 * This interface combines one Agent's five storage timelines with the
 * repository of its direct child Agents. [runtime] is created when this
 * session is opened and remains owned by the session's coroutine lifecycle.
 *
 * A newly created Agent is deliberately uninitialized. Its runtime initially
 * observes an empty state; the caller must initialize [runtime] before
 * requesting a model response. Directly initializing [storage] would bypass
 * the runtime's observable index and state publication.
 */
public interface CodexAgentSession : CoroutineScope {
    public val storage: MutableCodexAgentStorage

    public val subagents: CodexSessionRepository

    /**
     * The AgentRuntime owned by this open session.
     *
     * AgentRuntime already implements the complete AgentState contract, so the
     * session does not expose a second state reference.
     */
    public val runtime: AgentRuntime
}

/**
 * One collection of direct Agent entries in a recursive Codex session tree.
 *
 * Repeatedly opening the same active entry returns the same [CodexAgentSession]
 * instance. Root Agents and subagents share this contract; their runtime
 * composition is an implementation detail of the repository that creates
 * them.
 */
public interface CodexSessionRepository : CoroutineScope {
    /** Returns direct Agent entry indices in stable repository order. */
    public suspend fun list(): List<Int>

    /**
     * Creates one uninitialized direct Agent, then returns its entry index.
     *
     * After [open], initialize the returned session through its runtime.
     */
    public suspend fun create(): Int

    /** Opens one direct Agent entry. */
    public suspend fun open(entryIndex: Int): CodexAgentSession

    /** Removes one direct Agent entry and its complete descendant tree. */
    public suspend fun delete(entryIndex: Int)
}
