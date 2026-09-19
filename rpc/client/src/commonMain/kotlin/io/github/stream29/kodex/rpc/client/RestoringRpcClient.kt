package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.utils.rpcexception.restoreRemoteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

/**
 * Restores known remote exceptions and isolates upstream RPC collection from downstream failures.
 *
 * Create service proxies from this client, not from [delegate]. Existing proxies are unaffected.
 * Streaming calls cover both immediate Flow creation failures and later upstream failures.
 * Cancellation, unknown failures and downstream consumers' own exceptions are not reclassified.
 *
 * This adapter neither owns the connection nor retries calls, reconnects or restores state.
 * It does not start collecting a Flow until a caller collects the returned Flow.
 */
public class RestoringRpcClient(
    private val delegate: RpcClient,
) : RpcClient {
    override suspend fun <T> call(call: RpcCall): T =
        restoreRemoteException {
            delegate.call<T>(call)
        }

    override fun <T> callServerStreaming(call: RpcCall): Flow<T> =
        restoreRemoteException {
            delegate.callServerStreaming<T>(call)
        }
            .buffer(0)
            .catch { cause ->
                restoreRemoteException { throw cause }
            }
}
