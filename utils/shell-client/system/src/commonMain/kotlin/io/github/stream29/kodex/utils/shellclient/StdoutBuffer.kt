package io.github.stream29.kodex.utils.shellclient

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writable side of a [StdoutBuffer].
 *
 * Platform readers send owned byte arrays through this rendezvous channel.
 * [flush] establishes that every previously accepted array has reached the
 * readable state. This type is internal so callers only depend on [StdoutBuffer].
 */
internal interface MutableStdoutBuffer : StdoutBuffer, SendChannel<ByteArray> {
    suspend fun flush()

    fun signalTerminal()

    /** Stops the writer after all accepted output has reached the readable state. */
    fun finish()

    fun abort(cause: Throwable)
}

/** Creates the internal writable side while exposing [StdoutBuffer] to readers. */
internal fun MutableStdoutBuffer(
    scope: CoroutineScope,
    quotaByteCount: Int = StdoutBufferDefaultQuotaByteCount,
    headTailRetentionPercent: Int = StdoutBufferHeadTailRetentionPercent,
): MutableStdoutBuffer = MutableStdoutBufferImpl(
    scope = scope,
    quotaByteCount = quotaByteCount,
    headTailRetentionPercent = headTailRetentionPercent,
)

private class MutableStdoutBufferImpl(
    scope: CoroutineScope,
    quotaByteCount: Int,
    headTailRetentionPercent: Int,
    private val input: Channel<ByteArray> = Channel(Channel.RENDEZVOUS),
) : MutableStdoutBuffer, SendChannel<ByteArray> by input {
    private val ownerJob: Job? = scope.coroutineContext[Job]
    private val stateMutex: Mutex = Mutex()
    private val retainedOutput: RetainedStdoutBytes = RetainedStdoutBytes(
        quotaByteCount = quotaByteCount,
        headTailRetentionPercent = headTailRetentionPercent,
    )
    private val flushes: Channel<CompletableDeferred<Unit>> = Channel(Channel.RENDEZVOUS)
    private val terminal: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val abortCause: MutableStateFlow<CancellationException?> = MutableStateFlow(null)

    private val writer: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        consumeInput()
    }

    override suspend fun read(offset: ULong, limit: UInt): StdoutSlice = stateMutex.withLock {
        abortCause.value?.let { throw it }
        if (ownerJob?.isActive == false && !terminal.value) {
            ownerJob.ensureActive()
        }
        currentCoroutineContext().ensureActive()
        retainedOutput.read(offset = offset, limit = limit)
    }

    override suspend fun flush() {
        val completed: CompletableDeferred<Unit> = CompletableDeferred()
        flushes.send(completed)
        completed.await()
    }

    override fun signalTerminal() {
        terminal.value = true
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

private val EmptyByteArray: ByteArray = ByteArray(0)

/**
 * Non-destructive bounded stream retaining leading `head` (`headTailRetentionPercent` of [quotaByteCount])
 * and trailing `tail` (`headTailRetentionPercent` of [quotaByteCount]).
 */
private class RetainedStdoutBytes(
    quotaByteCount: Int,
    headTailRetentionPercent: Int,
) {
    private val headByteBudget: Int
    private val tailRing: RingBytes
    private var headBuffer: ByteArray = EmptyByteArray
    private var headSize: Int = 0
    private var totalByteCount: ULong = 0uL

    init {
        require(quotaByteCount >= 0) { "quotaByteCount must not be negative." }
        require(headTailRetentionPercent in 0..50) {
            "headTailRetentionPercent must be in 0..50."
        }
        headByteBudget = ((quotaByteCount.toLong() * headTailRetentionPercent) / 100L).toInt()
        val tailByteBudget = ((quotaByteCount.toLong() * headTailRetentionPercent) / 100L).toInt()
        tailRing = RingBytes(tailByteBudget)
    }

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        totalByteCount = totalByteCount.saturatingPlus(bytes.size.toULong())
        val remainingHead = headByteBudget - headSize
        val headLength = minOf(remainingHead, bytes.size).coerceAtLeast(0)
        if (headLength > 0) {
            ensureHeadCapacity(headSize + headLength)
            bytes.copyInto(
                destination = headBuffer,
                destinationOffset = headSize,
                startIndex = 0,
                endIndex = headLength,
            )
            headSize += headLength
        }
        if (headLength < bytes.size) {
            tailRing.append(bytes, offset = headLength)
        }
    }

    fun read(offset: ULong, limit: UInt): StdoutSlice {
        val total = totalByteCount
        if (offset >= total) {
            return StdoutSlice(
                head = EmptyByteArray,
                tail = EmptyByteArray,
                omittedByteCount = 0uL,
                offset = total,
                nextOffset = total,
                totalByteCount = total,
            )
        }
        if (limit == 0u) {
            return StdoutSlice(
                head = EmptyByteArray,
                tail = EmptyByteArray,
                omittedByteCount = 0uL,
                offset = offset,
                nextOffset = offset,
                totalByteCount = total,
            )
        }

        val headEnd = headSize.toULong()
        val tailSize = tailRing.size.toULong()
        val tailStart = total - tailSize

        var remaining = limit.toULong()
        var cursor = offset

        val headPart = if (cursor < headEnd) {
            val headTake = minOf(headEnd - cursor, remaining)
            val startIdx = cursor.toInt()
            val endIdx = (cursor + headTake).toInt()
            cursor += headTake
            remaining -= headTake
            headBuffer.copyOfRange(startIdx, endIdx)
        } else {
            EmptyByteArray
        }

        if (remaining == 0uL) {
            return StdoutSlice(
                head = headPart,
                tail = EmptyByteArray,
                omittedByteCount = 0uL,
                offset = offset,
                nextOffset = cursor,
                totalByteCount = total,
            )
        }

        val omitted = if (cursor < tailStart) {
            (tailStart - cursor).also { cursor = tailStart }
        } else {
            0uL
        }

        val tailAvailable = total - cursor
        val tailTake = minOf(tailAvailable, remaining)
        val tailPart = tailRing.slice(
            tailOffset = (cursor - tailStart).toInt(),
            length = tailTake.toInt(),
        )
        val nextOffset = cursor + tailTake

        if (omitted == 0uL) {
            val contiguous = when {
                headPart.isEmpty() -> tailPart
                tailPart.isEmpty() -> headPart
                else -> headPart + tailPart
            }
            return StdoutSlice(
                head = contiguous,
                tail = EmptyByteArray,
                omittedByteCount = 0uL,
                offset = offset,
                nextOffset = nextOffset,
                totalByteCount = total,
            )
        }

        return StdoutSlice(
            head = headPart,
            tail = tailPart,
            omittedByteCount = omitted,
            offset = offset,
            nextOffset = nextOffset,
            totalByteCount = total,
        )
    }

    private fun ensureHeadCapacity(required: Int) {
        if (headBuffer.size >= required) return
        val newCapacity = maxOf(
            required,
            minOf(headByteBudget, maxOf(256, headBuffer.size * 2)),
        )
        headBuffer = headBuffer.copyOf(newCapacity)
    }
}

private class RingBytes(
    private val capacity: Int,
) {
    private val buffer: ByteArray = if (capacity > 0) ByteArray(capacity) else EmptyByteArray
    private var start: Int = 0
    var size: Int = 0
        private set

    fun append(bytes: ByteArray, offset: Int) {
        val length = bytes.size - offset
        if (length <= 0 || capacity == 0) return

        if (length >= capacity) {
            bytes.copyInto(
                destination = buffer,
                destinationOffset = 0,
                startIndex = bytes.size - capacity,
                endIndex = bytes.size,
            )
            start = 0
            size = capacity
            return
        }

        if (size + length > capacity) {
            val dropped = (size + length) - capacity
            start = (start + dropped) % capacity
            size -= dropped
        }

        val writePos = (start + size) % capacity
        val firstPart = minOf(length, capacity - writePos)
        bytes.copyInto(
            destination = buffer,
            destinationOffset = writePos,
            startIndex = offset,
            endIndex = offset + firstPart,
        )
        if (firstPart < length) {
            bytes.copyInto(
                destination = buffer,
                destinationOffset = 0,
                startIndex = offset + firstPart,
                endIndex = offset + length,
            )
        }
        size += length
    }

    fun slice(tailOffset: Int, length: Int): ByteArray {
        if (length <= 0 || capacity == 0) return EmptyByteArray
        val out = ByteArray(length)
        val readPos = (start + tailOffset) % capacity
        val firstPart = minOf(length, capacity - readPos)
        buffer.copyInto(
            destination = out,
            destinationOffset = 0,
            startIndex = readPos,
            endIndex = readPos + firstPart,
        )
        if (firstPart < length) {
            buffer.copyInto(
                destination = out,
                destinationOffset = firstPart,
                startIndex = 0,
                endIndex = length - firstPart,
            )
        }
        return out
    }
}

private fun Throwable.asStdoutBufferCancellation(): CancellationException =
    this as? CancellationException
        ?: CancellationException(message ?: "Process output buffer was aborted.")

private fun ULong.saturatingPlus(other: ULong): ULong =
    if (this > ULong.MAX_VALUE - other) ULong.MAX_VALUE else this + other
