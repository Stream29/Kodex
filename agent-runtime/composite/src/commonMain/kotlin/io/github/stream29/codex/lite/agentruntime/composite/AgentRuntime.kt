package io.github.stream29.codex.lite.agentruntime.composite

import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgent
import io.github.stream29.codex.lite.openai.ContentItem
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A fully composed Agent runtime with externally controllable turn steering.
 *
 * @property pendingSteer Pending content for the current logical turn. An empty
 * list means that no steer is waiting.
 */
public interface AgentRuntime : ResumableAgent {
    public val pendingSteer: MutableStateFlow<List<ContentItem>>
}
