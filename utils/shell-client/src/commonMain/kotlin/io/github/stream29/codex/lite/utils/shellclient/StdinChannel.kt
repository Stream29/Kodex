package io.github.stream29.codex.lite.utils.shellclient

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.SelectClause2

/**
 * Maps public text input to ordered platform writes.
 *
 * A sender waits until the sole consumer settles its [PendingStdin]. Closing
 * the channel normally preserves a claimed write; aborting it settles that
 * write with the terminal failure before the consumer is stopped.
 */
internal class StdinChannel(
    scope: CoroutineScope,
    private val ownerJob: Job,
) : SendChannel<String>, ReceiveChannel<PendingStdin> {
    private val pending: Channel<PendingStdin> = Channel(Channel.RENDEZVOUS)
    private val active: MutableStateFlow<PendingStdin?> = MutableStateFlow(null)
    private val abortCause: MutableStateFlow<Throwable?> = MutableStateFlow(null)

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { cause ->
            cause?.let(::abort)
        }
        if (scope.coroutineContext[Job] !== ownerJob) {
            ownerJob.invokeOnCompletion { cause ->
                cause?.let(::abort)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override val isClosedForSend: Boolean
        get() = pending.isClosedForSend

    @OptIn(DelicateCoroutinesApi::class)
    override val isClosedForReceive: Boolean
        get() = pending.isClosedForReceive

    @OptIn(ExperimentalCoroutinesApi::class)
    override val isEmpty: Boolean
        get() = pending.isEmpty

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun send(element: String) {
        abortCause.value?.let { throw it }
        if (pending.isClosedForSend) {
            throw ClosedSendChannelException("Process standard input is closed.")
        }
        ownerJob.ensureActive()
        PendingStdin(element).also { next ->
            pending.send(next)
            next.written.await()
        }
    }

    /**
     * `trySend` can only report whether the rendezvous handoff was immediate;
     * unlike [send], it cannot wait for a suspendable platform write.
     */
    override fun trySend(element: String): ChannelResult<Unit> =
        pending.trySend(PendingStdin(element))

    /**
     * kotlinx.coroutines seals [SelectClause2], so a type-safe `String` to
     * [PendingStdin] adapter cannot expose `onSend` without leaking the
     * internal channel.
     */
    override val onSend: SelectClause2<String, SendChannel<String>>
        get() = throw UnsupportedOperationException("Process standard input does not support select onSend.")

    override suspend fun receive(): PendingStdin = pending.receive().also(::claim)

    /** Receiving through `select` cannot preserve claimed-write tracking. */
    override val onReceive: SelectClause1<PendingStdin>
        get() = throw UnsupportedOperationException("Process standard input does not support select onReceive.")

    override suspend fun receiveCatching(): ChannelResult<PendingStdin> =
        pending.receiveCatching().also { result -> result.getOrNull()?.let(::claim) }

    /** Receiving through `select` cannot preserve claimed-write tracking. */
    override val onReceiveCatching: SelectClause1<ChannelResult<PendingStdin>>
        get() = throw UnsupportedOperationException("Process standard input does not support select onReceiveCatching.")

    override fun tryReceive(): ChannelResult<PendingStdin> =
        pending.tryReceive().also { result -> result.getOrNull()?.let(::claim) }

    override fun iterator(): ChannelIterator<PendingStdin> {
        val delegate = pending.iterator()
        return object : ChannelIterator<PendingStdin> {
            override suspend fun hasNext(): Boolean = delegate.hasNext()

            override fun next(): PendingStdin = delegate.next().also(::claim)
        }
    }

    override fun cancel(cause: CancellationException?) {
        abort(cause ?: CancellationException("Process standard input was cancelled."))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    @Deprecated(
        level = DeprecationLevel.HIDDEN,
        message = "Since 1.2.0, binary compatibility with versions <= 1.1.x",
    )
    override fun cancel(cause: Throwable?): Boolean =
        abort(cause ?: CancellationException("Process standard input was cancelled."))

    override fun close(cause: Throwable?): Boolean =
        if (cause == null) {
            pending.close()
        } else {
            abort(cause)
        }

    override fun invokeOnClose(handler: (cause: Throwable?) -> Unit) {
        pending.invokeOnClose(handler)
    }

    /** Settles a successfully written value before allowing the next handoff. */
    internal fun succeed(next: PendingStdin) {
        next.written.complete(Unit)
        active.compareAndSet(next, null)
    }

    /** Settles a failed write before allowing the next handoff. */
    internal fun fail(next: PendingStdin, failure: Throwable) {
        next.written.completeExceptionally(failure)
        active.compareAndSet(next, null)
    }

    /** Rejects queued and already claimed values with a single terminal failure. */
    internal fun abort(cause: Throwable): Boolean {
        if (!abortCause.compareAndSet(null, cause)) return false
        pending.cancel(cause.asChannelCancellation())
        takeActive()?.written?.completeExceptionally(cause)
        return true
    }

    private fun claim(next: PendingStdin) {
        abortCause.value?.let { failure ->
            next.written.completeExceptionally(failure)
            return
        }
        check(active.compareAndSet(null, next)) { "Process standard input has more than one consumer." }
        abortCause.value?.let { failure ->
            if (active.compareAndSet(next, null)) {
                next.written.completeExceptionally(failure)
            }
        }
    }

    private fun takeActive(): PendingStdin? {
        while (true) {
            val current = active.value ?: return null
            if (active.compareAndSet(current, null)) return current
        }
    }
}

/** One UTF-8 standard-input write awaiting its platform completion. */
internal class PendingStdin(
    val text: String,
    val written: CompletableDeferred<Unit> = CompletableDeferred(),
)

private fun Throwable.asChannelCancellation(): CancellationException =
    this as? CancellationException
        ?: CancellationException(message ?: "Process standard input was aborted.")
