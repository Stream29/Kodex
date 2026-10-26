package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.CoroutineScope

/**
 * Bootstraps from the same-origin CLI frontend host, then connects directly to the backend.
 * [path] is the launch directory `/web/<id>/`; [ticket] is a frontend capability.
 * The entry HTML retains only that capability in tab-scoped sessionStorage for reload.
 * The scoped backend credential stays in page memory and never authorizes management.
 * The initial directory and exact backend instance are fixed; no discovery or replacement.
 * Closing this owner releases renewal/transport, not the Daemon or its accepted work.
 *
 * @throws IllegalArgumentException if the origin/path/ticket is malformed or not this page's origin.
 * @throws IllegalStateException if admission is unavailable, expired or incompatible.
 */
public expect suspend fun <R> withLocalWebClient(
    origin: String,
    path: String,
    ticket: String?,
    block: suspend CoroutineScope.(canonicalDirectory: String, FrontendRpcConnection) -> R,
): R
