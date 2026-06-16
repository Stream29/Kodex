package io.github.stream29.codex.lite.agentloop.contract

import io.github.stream29.codex.lite.utils.ReadWriteMutex
import kotlinx.coroutines.flow.StateFlow

/**
 * [AgentLoop] is a mutable instance. It may contain persistence logic.
 * It holds the immutable [AgentState].
 * The context compaction logic and context caching are handled by the [AgentLoop].
 */
public interface AgentLoop {
    public val rwStateFlow: StateFlow<ReadWriteMutex.State>

}

public interface AgentState<AgentConfig, AgentMessage> {
    public val config: AgentConfig
    public val history: AgentHistory<AgentMessage>
}

public interface AgentHistory<AgentMessage> {
    public suspend operator fun get(range: IntRange): List<AgentMessage>
    public suspend operator fun get(index: Int): AgentMessage
    public suspend fun getSize(): Int
}
