package io.github.stream29.codex.lite.agentcontext.contract

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage

/**
 * Supplies model context by source.
 *
 * ContextInjectingCodexAgentState owns when each source is requested.
 * Providers return only source-level content and never receive mutable
 * storage or construct OpenAI history items.
 */
public interface AgentContextProvider {
    /**
     * Provides the current AGENTS.md-derived text.
     *
     * A `null` result means this source has no model-visible update at the
     * current snapshot. The decorator owns conversion to model history.
     */
    public suspend fun provideAgentMd(
        snapshot: AgentContextSnapshot,
    ): String? = null
}

/**
 * Read-only state snapshot supplied to an [AgentContextProvider].
 *
 * [latestIndex] is captured with [state] before a provider callback runs.
 * Providers that read [storage] should use it as their snapshot upper bound.
 *
 * @property state Stable agent state at this lifecycle point.
 * @property latestIndex Latest visible storage index at this lifecycle point.
 * @property storage Read-only persisted thread data.
 */
public data class AgentContextSnapshot(
    public val state: CodexAgentStateValue,
    public val latestIndex: Int,
    public val storage: CodexAgentStorage,
)
