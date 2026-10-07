package io.github.stream29.kodex.rpc.inmemory

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
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
    // kRPC can try to enqueue a cancellation reply after its private send
    // queue was closed. This is teardown-only, not an ordinary command failure.
    @kotlin.concurrent.Volatile
    private var shuttingDown = false
    private val inheritedErrors = context[CoroutineExceptionHandler]
    override val coroutineContext: CoroutineContext = context + owner + CoroutineExceptionHandler { scope, failure ->
        if (!(shuttingDown && failure is ClosedSendChannelException)) {
            inheritedErrors?.handleException(scope, failure) ?: throw failure
        }
    }

    fun beginShutdown() {
        shuttingDown = true
    }

    override suspend fun send(message: KrpcTransportMessage) {
        check(message is KrpcTransportMessage.StringMessage) {
            "The in-memory RPC connection uses JSON string messages."
        }
        outgoing.send(message.value)
    }

    override suspend fun receive(): KrpcTransportMessage =
        KrpcTransportMessage.StringMessage(incoming.receive())
}
