@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.utils.rpcexception.restoreRemoteException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

public actual class RestoringRpcClient actual constructor(
    private val delegate: RpcClient,
) : RpcClient {
    actual override suspend fun <T> call(call: RpcCall): T =
        restoreRemoteException {
            delegate.call<T>(call)
        }

    actual override fun <T> callServerStreaming(call: RpcCall): Flow<T> =
        restoreRemoteException {
            delegate.callServerStreaming<T>(call)
        }
            .catch { cause ->
                currentCoroutineContext().ensureActive()
                restoreRemoteException { throw cause }
            }
            .buffer(0)
}
