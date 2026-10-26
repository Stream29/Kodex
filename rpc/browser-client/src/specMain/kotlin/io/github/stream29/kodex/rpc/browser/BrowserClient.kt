package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.LocalBrowserEndpoint
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.CoroutineScope

/**
 * Opt-in same-machine connection using an explicitly imported private connection record.
 * Credentials stay in memory. This is not remote authentication or backend discovery:
 * the operator must trust the local OS and the current loopback listener.
 * Owns renewal and transport, not accepted backend work; never retries one-shot RPC calls.
 * After initial admission, recognized network failures retry only this exact endpoint.
 * Explicit rejection publishes a failed connection rather than discovering a new instance.
 * Failures from [block] propagate; caller cancellation also propagates and releases owned resources.
 *
 * @throws IllegalArgumentException if the record's shape or business protocol is invalid.
 * @throws IllegalStateException if initial admission is rejected or the initial connection cannot be established.
 */
public expect suspend fun <R> withLocalBrowserClient(
    endpoint: LocalBrowserEndpoint,
    workingDirectory: String? = null,
    block: suspend CoroutineScope.(canonicalDirectory: String, FrontendRpcConnection) -> R,
): R
