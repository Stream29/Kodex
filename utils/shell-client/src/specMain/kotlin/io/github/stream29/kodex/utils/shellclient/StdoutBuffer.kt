package io.github.stream29.kodex.utils.shellclient

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

/** Fixed default output retention budget (`1 MiB`) for one [StdoutBuffer]. */
public const val StdoutBufferDefaultQuotaByteCount: Int = 1_024 * 1_024

/** Retained percentage of [StdoutBufferDefaultQuotaByteCount] for each of head and tail (`10%` head + `10%` tail). */
public const val StdoutBufferHeadTailRetentionPercent: Int = 10

/**
 * Non-destructively reads from a bounded byte stream produced by one process output channel.
 *
 * Logical stream offsets start at `0` and grow monotonically to [StdoutSlice.totalByteCount].
 * Implementations retain a bounded leading head `[0, headEnd)` and trailing tail
 * `[tailStart, totalByteCount)` when output exceeds the retention budget, omitting the middle
 * `[headEnd, tailStart)`. Multiple readers hold their own cursors independently without clearing
 * buffered bytes.
 */
public interface StdoutBuffer {
    /**
     * Non-destructively reads at most [limit] retained source bytes starting at logical stream byte
     * [offset] from one coherent buffer snapshot, without waiting for future output or consuming bytes.
     *
     * Range semantics on the captured snapshot (`totalByteCount`):
     * - When `offset >= totalByteCount`, returns an empty slice anchored at
     *   `offset = totalByteCount, nextOffset = totalByteCount`.
     * - When `limit == 0u`, returns an empty slice anchored at `nextOffset = offset`.
     * - For every returned slice, `nextOffset - offset == head.size.toULong() + omittedByteCount + tail.size.toULong()`.
     * - When `offset` falls inside the omitted middle `[headEnd, tailStart)`, `head` is empty,
     *   `omittedByteCount = tailStart - offset`, and up to [limit] bytes are read from `tail`.
     * - Raw source bytes are sliced without splitting or decoding UTF-8 sequences.
     *
     * @throws kotlinx.coroutines.CancellationException when the caller or nonterminal owner is cancelled.
     */
    public suspend fun read(
        offset: ULong = 0uL,
        limit: UInt = UInt.MAX_VALUE,
    ): StdoutSlice
}

/**
 * Pure transport DTO representing a non-destructive slice read from a [StdoutBuffer] over `[offset, nextOffset)`.
 *
 * [head] and [tail] contain only source bytes; when [omittedByteCount] is positive, that many
 * stream bytes were omitted between [head] and [tail] within `[offset, nextOffset)`.
 */
@Serializable
public data class StdoutSlice(
    public val head: ByteArray,
    public val tail: ByteArray,
    public val omittedByteCount: ULong,
    public val offset: ULong,
    public val nextOffset: ULong,
    public val totalByteCount: ULong,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is StdoutSlice &&
            head.contentEquals(other.head) &&
            tail.contentEquals(other.tail) &&
            omittedByteCount == other.omittedByteCount &&
            offset == other.offset &&
            nextOffset == other.nextOffset &&
            totalByteCount == other.totalByteCount

    override fun hashCode(): Int {
        var result = head.contentHashCode()
        result = 31 * result + tail.contentHashCode()
        result = 31 * result + omittedByteCount.hashCode()
        result = 31 * result + offset.hashCode()
        result = 31 * result + nextOffset.hashCode()
        result = 31 * result + totalByteCount.hashCode()
        return result
    }
}

/** Source bytes retained in this slice, excluding any omission marker. */
public val StdoutSlice.retainedByteCount: ULong
    get() = head.size.toULong() + tail.size.toULong()

public val StdoutSlice.isEmpty: Boolean
    get() = retainedByteCount == 0uL && omittedByteCount == 0uL

/** Renders the slice bytes, placing a textual omission marker between [StdoutSlice.head] and [StdoutSlice.tail] when needed. */
public fun StdoutSlice.renderedBytes(): ByteArray {
    val output = Buffer()
    output.write(head)
    if (omittedByteCount > 0uL) {
        output.write("\n... $omittedByteCount bytes omitted ...\n".encodeToByteArray())
    }
    output.write(tail)
    return output.readByteArray()
}
