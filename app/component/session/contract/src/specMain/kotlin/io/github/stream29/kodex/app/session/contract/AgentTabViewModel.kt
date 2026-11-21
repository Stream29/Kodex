package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Exact local page for one Agent in the selected Backend, stable across binding recovery. */
public interface AgentTabViewModel : AutoCloseable {
    public val agentId: Int
    public val name: StateFlow<String>
    public val agent: StateFlow<AgentViewModel?>
    public val lifecycle: StateFlow<PersistedSessionLifecycleState>

    /** Borrows this already-open page's current binding; never opens or renews another page. */
    public fun observeCompletionAnchor(childAgentId: Int): Flow<Boolean> = flowOf(false)

    /** Uses only [source]'s binding and rechecks [isCurrent] before publishing an exact scroll. */
    public suspend fun locateChildEvent(
        source: AgentViewModel, childAgentId: Int, anchor: AgentParentAnchor, isCurrent: () -> Boolean,
    ): Unit = Unit
}
