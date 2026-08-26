package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Frontend contract for one persisted root Session surface.
 *
 * The factory returns only after the stable root Agent is available. The
 * frontend reads root and selected Agent state directly from their handles.
 */
public interface PersistedSessionViewModel : SessionViewModel {
    public val sessionIndex: Int

    public val rootAgent: AgentViewModel
    public val selectedAgent: StateFlow<AgentViewModel>

    public val summary: StateFlow<PersistedSessionSummaryState>
    public val topology: StateFlow<PersistedSessionTopologyState>
    public val lifecycle: StateFlow<PersistedSessionLifecycleState>
    public val notification: StateFlow<PersistedSessionNotification?>

    /** Refreshes lightweight topology, summary, name, and root settings. */
    public suspend fun refresh(): Unit

    /**
     * Materializes [address] when necessary, then atomically publishes it as
     * selected and returns the shared stable handle.
     */
    public suspend fun selectAgent(address: AgentAddress): AgentViewModel

    /** Materializes at most the direct children of [parentAddress]. */
    public suspend fun materializeDirectChildren(parentAddress: AgentAddress): Unit

    /**
     * Forks the exact owned [source] through committed [target] into a new
     * persisted root Session and returns its index.
     *
     * A foreign child handle, stale target, or running source fails without
     * modifying this Session. Forking does not change application navigation or
     * open the returned Session.
     */
    public suspend fun fork(
        source: AgentViewModel,
        target: AgentHistoryTarget,
    ): Int

    /** Forks the complete current root storage into a new root Session. */
    public suspend fun fork(): Int

    public fun dismissNotification(notificationId: Long): Unit

    /**
     * Stops new commands and closes all materialized children in ownership
     * order. Repeated calls are idempotent.
     */
    public suspend fun shutdown(): Unit
}

/**
 * Opens one persisted Session and returns its stable ViewModel hierarchy.
 *
 * Opening failures occur before a child hierarchy exists and therefore escape
 * from this factory. Failures after opening are published by the returned
 * ViewModel.
 */
public fun interface PersistedSessionViewModelFactory {
    public suspend fun open(sessionIndex: Int): PersistedSessionViewModel
}
