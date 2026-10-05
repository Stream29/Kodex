package io.github.stream29.kodex.utils.shellclient

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
    /**
     * Atomically consumes output already buffered at the time of the call.
     *
     * @throws kotlinx.coroutines.CancellationException when the caller or nonterminal owner is cancelled.
     */
    public suspend fun drain(): StdoutBufferSnapshot

    /**
     * Consumes available output, waiting for at most [yieldTime] when empty.
     * A terminal buffer returns immediately with an empty snapshot.
     *
     * @throws IllegalArgumentException when [yieldTime] is negative.
     * @throws kotlinx.coroutines.CancellationException when the caller or nonterminal owner is cancelled.
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

private fun Long.saturatingPlus(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
