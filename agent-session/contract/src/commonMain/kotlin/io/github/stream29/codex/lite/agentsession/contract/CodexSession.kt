package io.github.stream29.codex.lite.agentsession.contract

import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import kotlinx.coroutines.CoroutineScope

/**
 * One exclusively owned Agent node in a recursive Codex session tree.
 *
 * This interface combines one Agent's five storage timelines with the
 * repository of its direct child Agents. It does not represent the Agent's
 * runtime state or model-call lifecycle.
 *
 * A newly created child is deliberately uninitialized. The caller must copy or
 * publish the snapshot-zero settings and compaction checkpoint before
 * constructing an AgentState from it.
 */
public interface CodexAgentSession : CoroutineScope {
    public val storage: MutableCodexAgentStorage

    public val subagents: CodexSessionRepository
}

/**
 * One collection of direct Agent entries in a recursive Codex session tree.
 *
 * Repeatedly opening the same active entry returns the same [CodexAgentSession]
 * instance.
 */
public interface CodexSessionRepository : CoroutineScope {
    /** Returns direct Agent entry indices in stable repository order. */
    public suspend fun list(): List<Int>

    /** Creates one uninitialized direct Agent, then returns its entry index. */
    public suspend fun create(): Int

    /** Opens one direct Agent entry. */
    public suspend fun open(entryIndex: Int): CodexAgentSession

    /** Removes one direct Agent entry and its complete descendant tree. */
    public suspend fun delete(entryIndex: Int)
}
