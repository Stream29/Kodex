@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.local

import io.github.stream29.kodex.backend.BackendEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

/** One-shot commands fail offline; only streaming observations re-subscribe on the same instance. */
public expect class LocalRpcConnection : FrontendRpcConnection {
    override val state: StateFlow<BackendConnectionState>
    override val failureReason: StateFlow<String?>
    override val available: Boolean
    override fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit)
    override suspend fun <T> call(call: RpcCall): T
    override fun <T> callServerStreaming(call: RpcCall): Flow<T>
}

public expect suspend fun <R> withLocalRpcClient(
    home: Path,
    endpoint: BackendEndpoint,
    block: suspend CoroutineScope.(LocalRpcConnection) -> R,
): R
