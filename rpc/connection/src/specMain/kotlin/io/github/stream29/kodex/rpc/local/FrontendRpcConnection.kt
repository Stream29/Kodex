@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

/** Client-owned connectivity, never a backend lifecycle-management capability. */
public enum class BackendConnectionState { Connected, Reconnecting, Failed }

public interface FrontendRpcConnection : RpcClient {
    public val state: StateFlow<BackendConnectionState>
    public val failureReason: StateFlow<String?>
    public val available: Boolean
    /** Binds a one-shot command or operation stream to the current peer without reconnection. */
    public fun bindOperation(): RpcClient
    /**
     * Rebind frontend-owned resources before publishing a replacement connection.
     * The predicate describes that exact replacement transport, not an old connection.
     * Live-transport business failures are not network retry signals.
     */
    public fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit)
}

/** Bound client and transport liveness probe for one physical RPC session. */
public class ActiveRpcPeer(
    public val client: RpcClient,
    public val alive: () -> Boolean,
)

/**
 * Shared frontend RPC connection state machine across Native CLI and Browser Wasm transports:
 * enforces offline rejection, unknown-write disconnect classification, pre-publication recovery,
 * terminal failure transition, and stream resubscription across peer replacements.
 */
public expect class ManagedFrontendRpcConnection() : FrontendRpcConnection {
    override val state: StateFlow<BackendConnectionState>
    override val failureReason: StateFlow<String?>
    override val available: Boolean
    override fun bindOperation(): RpcClient
    override fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit)
    public suspend fun install(peer: ActiveRpcPeer)
    public fun detach(peer: ActiveRpcPeer)
    public fun fail(reason: String?)
    override suspend fun <T> call(call: RpcCall): T
    override fun <T> callServerStreaming(call: RpcCall): Flow<T>
}
