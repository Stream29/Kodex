package io.github.stream29.kodex.rpc.localserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.rpc.RpcServer

/** Established by the server's transport admission, never supplied by a caller. */
public enum class BackendConnectionAdmission { LocalCredential, BrowserGrant }

/** One port, one external service graph, independent resources for each authenticated connection. */
public expect suspend fun <R> withLocalBackendServer(
    password: String,
    instanceId: String,
    control: BackendControl,
    registerServices: RpcServer.(BackendConnectionAdmission) -> Unit,
    browserAccess: LocalBrowserAccess? = null,
    block: suspend CoroutineScope.(Int) -> R,
): R
