package io.github.stream29.kodex.app.webrenderersmoke

import io.github.stream29.kodex.cli.rpc.RpcServices
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

/** Exercises the production constructor without a backend, credentials or direct class-literal roots. */
internal fun checkRpcDescriptorAssembly(): String = try {
    RpcServices(object : RpcClient {
        override suspend fun <T> call(call: RpcCall): T = error("Descriptor smoke must not call RPC.")
        override fun <T> callServerStreaming(call: RpcCall): Flow<T> = error("Descriptor smoke must not subscribe.")
    })
    "All eight production RPC proxies constructed."
} catch (error: Exception) {
    "Descriptor assembly failed: ${error.message}"
}
