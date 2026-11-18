package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.CoroutineScope

/** Bootstraps and uses the exact local backend. Closing the page closes only its connection. */
public expect suspend fun <R> withLocalFrontendClient(
    origin: String,
    path: String,
    ticket: String?,
    block: suspend CoroutineScope.(String, FrontendRpcConnection) -> R,
): R
