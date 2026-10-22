@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.websocket

import io.ktor.websocket.WebSocketSession
import kotlinx.rpc.krpc.KrpcTransport
import kotlinx.rpc.krpc.KrpcTransportMessage
import kotlin.coroutines.CoroutineContext

public expect class WebSocketRpcTransport(
    socket: WebSocketSession,
    context: CoroutineContext,
) : KrpcTransport {
    public var alive: Boolean
        private set
    override val coroutineContext: CoroutineContext
    override suspend fun send(message: KrpcTransportMessage)
    override suspend fun receive(): KrpcTransportMessage
    public suspend fun awaitClosed()
    public suspend fun shutdown()
}
