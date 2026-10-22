package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.rpc.contract.TimelineRpc
import kotlinx.coroutines.CoroutineScope

/**
 * Opens one frontend timeline binding in this owner's lifetime.
 *
 * [rpc] must come from the restoring client. The Session must already be active.
 * Only existing exact entries are cached; metadata is initialized once and then observed.
 * Cancelling the owner releases this binding, not the shared client or backend Session.
 * Metadata failure invalidates the binding and follows the owner's exception policy.
 * There is no activation, resubscription, query retry or frontend-generated nonce.
 */
public expect suspend fun <T : Any> CoroutineScope.rpcCachedIndexVersioned(
    sessionIndex: Int,
    rpc: TimelineRpc<T>,
): CachedIndexVersioned<T>
