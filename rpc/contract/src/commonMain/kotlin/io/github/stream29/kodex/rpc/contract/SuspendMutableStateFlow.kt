package io.github.stream29.kodex.rpc.contract

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.StateFlow

/**
 * A local state projection with a suspending, authoritative compare-and-set operation.
 *
 * This is a client-side handle, not an RPC service or a value sent over RPC.
 * The inherited StateFlow reads the latest subscribed value and may lag behind the backend.
 * There is no setter, emit, or unconditional replacement operation.
 *
 * Implementations initialize from backend reads and maintain a shared subscription.
 * A timeline-backed projection reads values at the metadata it observes; those data queries
 * are not polling an initialization-only snapshot Get. Successful writes do not imply that
 * the projection has caught up.
 * Failed comparisons retry from the latest subscribed value. Without subscription progress,
 * cancellable, paced retries are allowed: conflation may hide intermediate changes entirely.
 * Do not spin on the same snapshot or retry against an invalidated projection.
 * Get is not a retry or refresh mechanism.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
public interface SuspendMutableStateFlow<T> : StateFlow<T> {
    /**
     * Compares and replaces the authoritative value atomically using value equality.
     * False means a comparison mismatch, not a validation or transport failure.
     * Equal expected, updated and current values succeed without changing state.
     *
     * A transport failure does not establish whether the write occurred.
     * Implementations must not overwrite a newer subscribed value with the write's result.
     */
    public suspend fun compareAndSet(expect: T, update: T): Boolean
}
