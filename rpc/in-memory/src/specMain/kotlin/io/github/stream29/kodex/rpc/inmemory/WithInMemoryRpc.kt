package io.github.stream29.kodex.rpc.inmemory

import kotlinx.coroutines.CoroutineScope
import kotlinx.rpc.RpcClient
import kotlinx.rpc.RpcServer

/**
 * Registers services and uses a single JSON kRPC connection within [block].
 *
 * The client, service proxies and connection-owned resources must not escape this scope.
 * [block] and its children finish before normal connection teardown. Service implementations'
 * external resources remain owned by their caller; this function does not close arbitrary objects.
 *
 * A failed call or cancelled subscription does not close the connection. Unexpected endpoint
 * termination fails this scope, cancelling its users without reconnecting or replaying calls.
 * Registration failure, block failure and outer cancellation all clean up both endpoints.
 * The client is raw kRPC: known-exception restoration and Flow protection belong in its adapters.
 */
public expect suspend fun <R> withInMemoryRpc(
    registerServices: RpcServer.() -> Unit,
    block: suspend CoroutineScope.(RpcClient) -> R,
): R
