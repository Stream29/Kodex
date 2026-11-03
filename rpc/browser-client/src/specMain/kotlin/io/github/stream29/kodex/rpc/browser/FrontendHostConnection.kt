package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Borrowed, exact-owner Native companion. Operations are never automatically retried. */
public interface FrontendHostConnection {
    public val descriptor: LocalFrontendHostDescriptor
    public val capabilities: StateFlow<FrontendHostCapabilities>
    public suspend fun call(request: LocalFrontendHostRequest): LocalFrontendHostReply
}

/** Acquires a new page owner; owns its companion lease and scoped direct business connection. */
public expect suspend fun <R> withLocalFrontendClient(
    origin: String,
    path: String,
    ticket: String?,
    block: suspend CoroutineScope.(String, FrontendRpcConnection, FrontendHostConnection) -> R,
): R

/** Consumes the explicitly selected one-time scoped offer, never a backend management record. */
public expect suspend fun <R> withPairedFrontendClient(
    offer: LocalFrontendPairingOffer,
    block: suspend CoroutineScope.(String, FrontendRpcConnection, FrontendHostConnection) -> R,
): R
