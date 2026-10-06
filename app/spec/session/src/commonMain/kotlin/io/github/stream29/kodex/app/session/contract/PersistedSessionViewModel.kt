package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Frontend contract for one persisted root Session surface.
 *
 * The factory returns after the first root Agent is available. This tab handle
 * stays stable across read-binding recovery, but its borrowed [rootAgent] does
 * not. It owns local read projections, not the remote repository/resource graph.
 * Settings/model inputs come through the original typed creation/registry seam.
 *
 * The renderer reads each exact Agent child directly and must not independently
 * close it on unmount. Last-known Session settings/name may remain visible during
 * recovery; they are read caches, not command admission or a writable second truth.
 * Commands require the current ready binding and its exact settings/Agent instance.
 * Caller cancellation stops local waiting, not backend work already accepted;
 * no automatic command replay follows recovery.
 */
public interface PersistedSessionViewModel : SessionViewModel {
    /** Existing RPC address, not a new permanent remote identity or lease. */
    public val sessionIndex: Int

    /** Borrowed child replaced on reactivation; null means no current renderable Agent. */
    public val rootAgent: StateFlow<AgentViewModel?>

    /** Local loading/open/failure/closed rendering; independent of Agent running state. */
    public val lifecycle: StateFlow<PersistedSessionLifecycleState>

    /**
     * Validates the current read binding; subscriptions own the name/settings projection.
     * @throws IllegalStateException if the view is not ready.
     * @throws kotlinx.coroutines.CancellationException if its owner is closed.
     */
    public suspend fun refresh(): Unit

    /**
     * Reads the first stored timestamp in index order on each menu opening.
     * @return null when the timestamp timeline is empty.
     * @throws IllegalStateException if the current read binding is not ready.
     * @throws Exception if the timeline/cache/storage/transport read fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting or owner is cancelled.
     */
    public suspend fun readCreatedAt(): Instant?

    /**
     * Reads the current timeline's latest timestamp on demand, not a permanent menu cache.
     * @return null when the timestamp timeline is empty.
     * @throws IllegalStateException if the current read binding is not ready.
     * @throws Exception if the timeline/cache/storage/transport read fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting or owner is cancelled.
     */
    public suspend fun readUpdatedAt(): Instant?

    /**
     * Forks the exact current [source] before [untilExclusive] into a new
     * persisted root Session and returns its index.
     *
     * The boundary preserves initialization and need not correspond to a materialized row.
     * A foreign child handle, stale generation, invalid boundary, or running source fails without
     * modifying this Session. Forking does not change application navigation or
     * open the returned Session. Local admission captures the current binding;
     * the backend retains its cache/generation/running checks.
     *
     * @throws IllegalArgumentException if source is not this handle's current exact Agent.
     * @throws IllegalStateException if the current binding/Agent is not ready.
     * @throws Exception if generation/boundary/running admission, storage, or transport fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting or owner is cancelled;
     * accepted backend work is not undone.
     */
    public suspend fun fork(
        source: AgentViewModel,
        untilExclusive: Int,
        expectedGeneration: Long,
    ): Int

    /**
     * Forks the complete persisted root storage without changing navigation.
     * @throws Exception if source admission, storage, or transport fails.
     * @throws kotlinx.coroutines.CancellationException if caller waiting is cancelled.
     */
    public suspend fun fork(): Int

    /**
     * Closes the owning RPC view first, then the local projection owner, and
     * waits for both. Repeated calls are idempotent. The borrowed Agent is
     * released by that view, never by an independent Session Agent owner.
     * Does not Stop backend work or close shared RPC/Home resources.
     *
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled
     * during cleanup waiting; immediate [close] has already withdrawn the view.
     */
    public suspend fun shutdown(): Unit
}

/**
 * Opens one persisted Session and returns its stable frontend handle.
 *
 * Opening failures before the first hierarchy escape from this factory. Later
 * read failures publish through the handle; recovery replaces its borrowed Agent.
 * Cancelling one opener only cancels that caller's wait, not a still-initializing
 * shared RPC view. Implementations retain original typed dependency inputs.
 */
public fun interface PersistedSessionViewModelFactory {
    /**
     * @throws Exception if initial activation/reads fail; no successful handle is returned.
     * @throws kotlinx.coroutines.CancellationException if caller waiting or owner is cancelled.
     */
    public suspend fun open(sessionIndex: Int): PersistedSessionViewModel
}
