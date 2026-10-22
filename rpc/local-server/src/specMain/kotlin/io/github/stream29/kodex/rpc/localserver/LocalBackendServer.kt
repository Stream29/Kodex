package io.github.stream29.kodex.rpc.localserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.rpc.RpcServer

/** One port, one external service graph, independent resources for each authenticated connection. */
public expect suspend fun <R> withLocalBackendServer(
    password: String,
    instanceId: String,
    control: BackendControl,
    registerServices: RpcServer.() -> Unit,
    block: suspend CoroutineScope.(Int) -> R,
): R
