package io.github.stream29.kodex.rpc.local

import kotlinx.coroutines.flow.StateFlow
import kotlinx.rpc.RpcClient

/** Client-owned connectivity, never a backend lifecycle-management capability. */
public enum class BackendConnectionState { Connected, Reconnecting, Failed }

public interface FrontendRpcConnection : RpcClient {
    public val state: StateFlow<BackendConnectionState>
    public val failureReason: StateFlow<String?>
    public val available: Boolean
    /**
     * Rebind frontend-owned resources before publishing a replacement connection.
     * The predicate describes that exact replacement transport, not an old connection.
     * Live-transport business failures are not network retry signals.
     */
    public fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit)
}
