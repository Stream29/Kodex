package io.github.stream29.kodex.rpc.inmemory

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.rpc.krpc.KrpcTransport
import kotlinx.rpc.krpc.KrpcTransportMessage
import kotlin.coroutines.CoroutineContext

/** Each endpoint owns a separate child Job; the enclosing connection owns both channels. */
internal class ChannelRpcTransport(
    context: CoroutineContext,
    private val incoming: Channel<String>,
    private val outgoing: Channel<String>,
) : KrpcTransport {
    val owner = Job(context.job)
    override val coroutineContext: CoroutineContext = context + owner

    override suspend fun send(message: KrpcTransportMessage) {
        check(message is KrpcTransportMessage.StringMessage) {
            "The in-memory RPC connection uses JSON string messages."
        }
        outgoing.send(message.value)
    }

    override suspend fun receive(): KrpcTransportMessage =
        KrpcTransportMessage.StringMessage(incoming.receive())
}
