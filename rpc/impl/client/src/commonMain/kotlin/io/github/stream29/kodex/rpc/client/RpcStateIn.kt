package io.github.stream29.kodex.rpc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Reads one real initial value before sharing updates in this caller-owned scope.
 *
 * [get] runs in the calling coroutine. Only after it succeeds is [getFlow] invoked.
 * Updates are shared eagerly, even without local collectors, until this scope is cancelled
 * or the upstream ends. Returning does not acknowledge that the remote subscription is bound.
 *
 * This uses [stateIn] without dropping the first update or retrying failures. Upstream failures
 * belong to this scope, not StateFlow subscribers. The owner handles invalidation and recovery;
 * this factory creates no independent scope and never re-reads [get] to refresh the state.
 */
public suspend fun <T> CoroutineScope.rpcStateIn(
    get: suspend () -> T,
    getFlow: () -> Flow<T>,
): StateFlow<T> {
    val initialValue = get()
    return getFlow().stateIn(this, SharingStarted.Eagerly, initialValue)
}
