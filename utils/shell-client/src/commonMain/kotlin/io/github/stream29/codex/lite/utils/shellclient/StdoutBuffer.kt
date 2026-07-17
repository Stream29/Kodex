package io.github.stream29.codex.lite.utils.shellclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.time.Duration

/**
 * Destructively reads the bytes accumulated by one output producer.
 *
 * Each [drain] returns ownership of every byte retained since the preceding
 * call. Implementations may retain only a bounded head and tail of oversized
 * output; [StdoutBufferSnapshot.omittedByteCount] reports the removed middle bytes.
 *
 * The destructive read is cancellation-safe: cancellation before the snapshot
 * leaves buffered bytes intact, while a completed call returns that snapshot.
 */
public interface StdoutBuffer {
    /** Atomically consumes output already buffered at the time of the call. */
    public suspend fun drain(): StdoutBufferSnapshot

    /**
     * Consumes available output, waiting for at most [yieldTime] when empty.
     * A terminal buffer returns immediately with an empty snapshot.
     */
    public suspend fun read(yieldTime: Duration): StdoutBufferSnapshot
}

/**
 * One destructive output snapshot.
 *
 * [head] and [tail] contain only source bytes. When [omittedByteCount] is
 * positive, the removed middle lies between them. [renderedBytes] adds a
 * textual omission marker for text-facing callers.
 */
public data class StdoutBufferSnapshot(
    public val head: ByteArray,
    public val tail: ByteArray,
    public val omittedByteCount: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is StdoutBufferSnapshot &&
            head.contentEquals(other.head) &&
            tail.contentEquals(other.tail) &&
            omittedByteCount == other.omittedByteCount

    override fun hashCode(): Int =
        31 * (31 * head.contentHashCode() + tail.contentHashCode()) + omittedByteCount.hashCode()

    /** Source bytes retained by this snapshot, excluding any omission marker. */
    public val retainedByteCount: Long
        get() = head.size.toLong().saturatingPlus(tail.size.toLong())

    /** Source bytes observed before bounded retention removed a middle section. */
    public val originalByteCount: Long
        get() = retainedByteCount.saturatingPlus(omittedByteCount)

    public val isEmpty: Boolean
        get() = originalByteCount == 0L

    /**
     * Renders the retained bytes, placing an omission marker between [head] and
     * [tail] when necessary.
     */
    public fun renderedBytes(): ByteArray {
        val output = Buffer()
        output.write(head)
        if (omittedByteCount > 0L) {
            output.write("\n... $omittedByteCount bytes omitted ...\n".encodeToByteArray())
        }
        output.write(tail)
        return output.readByteArray()
    }
}

/**
 * Writable side of a [StdoutBuffer].
 *
 * Platform readers send owned byte arrays through this rendezvous channel.
 * [flush] establishes that every previously accepted array has reached the
 * readable state. This type is internal so callers only depend on [StdoutBuffer].
 */
internal interface MutableStdoutBuffer : StdoutBuffer, SendChannel<ByteArray> {
    val changeVersion: StateFlow<Long>

    suspend fun drainSnapshot(): VersionedStdoutBufferSnapshot

    suspend fun flush()

    fun signalTerminal()

    /** Stops the writer after all accepted output has reached the readable state. */
    fun finish()

    fun abort(cause: Throwable)
}

/** Maximum retained process output before its middle is omitted. */
internal const val DefaultProcessOutputMaximumRetainedByteCount: Int = 1_024 * 1_024

/** Creates the internal writable side while exposing [StdoutBuffer] to readers. */
internal fun MutableStdoutBuffer(
    scope: CoroutineScope,
    maximumRetainedByteCount: Int = DefaultProcessOutputMaximumRetainedByteCount,
): MutableStdoutBuffer = MutableStdoutBufferImpl(scope, maximumRetainedByteCount)

/** Snapshot version captured atomically with one [MutableStdoutBuffer.drainSnapshot]. */
internal data class VersionedStdoutBufferSnapshot(
    val output: StdoutBufferSnapshot,
    val changeVersion: Long,
    val isTerminal: Boolean,
)

private class MutableStdoutBufferImpl(
    scope: CoroutineScope,
    maximumRetainedByteCount: Int,
    private val input: Channel<ByteArray> = Channel(Channel.RENDEZVOUS),
) : MutableStdoutBuffer, SendChannel<ByteArray> by input {
    private val ownerJob: Job? = scope.coroutineContext[Job]
    private val stateMutex: Mutex = Mutex()
    private val retainedOutput: HeadTailBytes = HeadTailBytes(maximumRetainedByteCount)
    private val flushes: Channel<CompletableDeferred<Unit>> = Channel(Channel.RENDEZVOUS)
    override val changeVersion: StateFlow<Long>
        field = MutableStateFlow(0L)
    private val terminal: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val abortCause: MutableStateFlow<CancellationException?> = MutableStateFlow(null)

    private val writer: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        consumeInput()
    }

    override suspend fun drain(): StdoutBufferSnapshot = drainSnapshot().output

    override suspend fun read(yieldTime: Duration): StdoutBufferSnapshot {
        require(!yieldTime.isNegative()) { "yieldTime must not be negative." }
        val initial: VersionedStdoutBufferSnapshot = drainSnapshot()
        if (!initial.output.isEmpty || initial.isTerminal) return initial.output

        withTimeoutOrNull(yieldTime) {
            changeVersion.first { version -> version != initial.changeVersion }
        }
        return drain()
    }

    override suspend fun drainSnapshot(): VersionedStdoutBufferSnapshot = stateMutex.withLock {
        abortCause.value?.let { throw it }
        if (ownerJob?.isActive == false && !terminal.value) {
            ownerJob.ensureActive()
        }
        currentCoroutineContext().ensureActive()
        VersionedStdoutBufferSnapshot(
            output = retainedOutput.drain(),
            changeVersion = changeVersion.value,
            isTerminal = terminal.value,
        )
    }

    override suspend fun flush() {
        val completed: CompletableDeferred<Unit> = CompletableDeferred()
        flushes.send(completed)
        completed.await()
    }

    override fun signalTerminal() {
        terminal.value = true
        changeVersion.update { version -> version + 1L }
    }

    override fun finish() {
        input.close()
        flushes.close()
        writer.cancel()
    }

    override fun abort(cause: Throwable) {
        val cancellation: CancellationException = cause.asStdoutBufferCancellation()
        if (!abortCause.compareAndSet(null, cancellation)) return
        signalTerminal()
        input.cancel(cancellation)
        flushes.cancel(cancellation)
        writer.cancel(cancellation)
    }

    private suspend fun consumeInput() {
        try {
            while (
                select {
                    input.onReceiveCatching { result ->
                        result.getOrNull()?.let { bytes ->
                            stateMutex.withLock {
                                retainedOutput.append(bytes)
                                changeVersion.update { version -> version + 1L }
                            }
                        }
                        !result.isClosed
                    }
                    flushes.onReceiveCatching { result ->
                        result.getOrNull()?.complete(Unit)
                        !result.isClosed
                    }
                }
            ) {
                // The selected channel remains open; keep consuming it.
            }
            for (flush in flushes) {
                flush.complete(Unit)
            }
        } finally {
            val cancellation = CancellationException("Process stdout buffer was closed.")
            input.cancel(cancellation)
            flushes.cancel(cancellation)
        }
    }
}

/** Bounded byte retention matching unified-exec's stable head/tail policy. */
private class HeadTailBytes(maximumRetainedByteCount: Int) {
    private val headByteBudget: Int
    private val tailByteBudget: Int
    private val head: Buffer = Buffer()
    private val tail: Buffer = Buffer()
    private var omittedByteCount: Long = 0L

    init {
        require(maximumRetainedByteCount >= 0) {
            "maximumRetainedByteCount must not be negative."
        }
        headByteBudget = maximumRetainedByteCount / 2
        tailByteBudget = maximumRetainedByteCount - headByteBudget
    }

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        var offset = 0
        val remainingHead: Long = (headByteBudget.toLong() - head.size).coerceAtLeast(0L)
        val headLength: Int = minOf(remainingHead, bytes.size.toLong()).toInt()
        if (headLength > 0) {
            head.write(bytes, offset, offset + headLength)
            offset += headLength
        }
        appendTail(bytes, offset)
    }

    fun drain(): StdoutBufferSnapshot {
        val drainedHead: ByteArray = head.readByteArray()
        val drainedTail: ByteArray = tail.readByteArray()
        val omitted: Long = omittedByteCount
        head.clear()
        tail.clear()
        omittedByteCount = 0L
        return StdoutBufferSnapshot(
            head = drainedHead,
            tail = drainedTail,
            omittedByteCount = omitted,
        )
    }

    private fun appendTail(bytes: ByteArray, offset: Int) {
        val remainingByteCount: Int = bytes.size - offset
        if (remainingByteCount == 0) return
        if (tailByteBudget == 0) {
            omittedByteCount = omittedByteCount.saturatingPlus(remainingByteCount.toLong())
            return
        }
        if (remainingByteCount >= tailByteBudget) {
            omittedByteCount = omittedByteCount
                .saturatingPlus(tail.size)
                .saturatingPlus((remainingByteCount - tailByteBudget).toLong())
            tail.clear()
            tail.write(bytes, bytes.size - tailByteBudget, bytes.size)
            return
        }

        tail.write(bytes, offset, bytes.size)
        val excessByteCount: Long = tail.size - tailByteBudget.toLong()
        if (excessByteCount > 0L) {
            tail.skip(excessByteCount)
            omittedByteCount = omittedByteCount.saturatingPlus(excessByteCount)
        }
    }
}

private fun Throwable.asStdoutBufferCancellation(): CancellationException =
    this as? CancellationException
        ?: CancellationException(message ?: "Process output drain was aborted.")

private fun Long.saturatingPlus(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
