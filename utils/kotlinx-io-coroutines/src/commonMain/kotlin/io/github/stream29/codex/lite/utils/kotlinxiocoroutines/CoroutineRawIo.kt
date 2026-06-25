package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray

internal const val CoroutineIoSegmentByteCount: Int = 64 * 1024

/**
 * Coroutine-friendly close boundary for resources whose close operation can perform I/O.
 */
public interface CoroutineCloseable {
    public suspend fun close()
}

/**
 * Coroutine counterpart of `kotlinx-io` `RawSource`.
 *
 * Implementations are not required to be thread-safe. Callers must avoid concurrent reads
 * on the same source instance and must close it after use.
 */
public interface CoroutineRawSource : CoroutineCloseable {
    public suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long
}

/**
 * Coroutine counterpart of `kotlinx-io` `RawSink`.
 *
 * Implementations are not required to be thread-safe. Callers must avoid concurrent writes
 * on the same sink instance and must close it after use.
 */
public interface CoroutineRawSink : CoroutineCloseable {
    public suspend fun write(source: Buffer, byteCount: Long)

    public suspend fun flush()
}

public suspend inline fun <T : CoroutineCloseable, R> T.use(block: suspend (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (throwable: Throwable) {
        failure = throwable
        throw throwable
    } finally {
        try {
            close()
        } catch (closeFailure: Throwable) {
            val originalFailure = failure
            if (originalFailure == null) {
                throw closeFailure
            }
            originalFailure.addSuppressed(closeFailure)
        }
    }
}

public suspend fun CoroutineRawSource.copyTo(
    sink: CoroutineRawSink,
    byteCount: Long = Long.MAX_VALUE,
): Long {
    require(byteCount >= 0L) { "byteCount: $byteCount" }
    val buffer = Buffer()
    var remaining = byteCount
    var copied = 0L
    while (remaining > 0L) {
        val read = readAtMostTo(buffer, minOf(remaining, CoroutineIoSegmentByteCount.toLong()))
        if (read == -1L) break
        copied += read
        remaining -= read
        sink.write(buffer, read)
    }
    return copied
}

public suspend fun CoroutineRawSource.readBytes(maxByteCount: Long = Long.MAX_VALUE): ByteArray {
    require(maxByteCount >= 0L) { "maxByteCount: $maxByteCount" }
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val remaining = maxByteCount - total
        if (remaining == 0L) {
            val extra = readAtMostTo(Buffer(), 1L)
            if (extra != -1L) {
                throw IOException("Source exceeds max byte count: $maxByteCount")
            }
            break
        }
        val read = readAtMostTo(buffer, minOf(remaining, CoroutineIoSegmentByteCount.toLong()))
        if (read == -1L) break
        total += read
        if (total > Int.MAX_VALUE) {
            throw IOException("Source is too large to fit in ByteArray")
        }
    }
    return buffer.readByteArray()
}

public suspend fun CoroutineRawSink.writeBytes(
    source: ByteArray,
    startIndex: Int = 0,
    endIndex: Int = source.size,
) {
    require(startIndex in 0..source.size) { "startIndex: $startIndex, size: ${source.size}" }
    require(endIndex in startIndex..source.size) { "endIndex: $endIndex, startIndex: $startIndex, size: ${source.size}" }
    var offset = startIndex
    val buffer = Buffer()
    while (offset < endIndex) {
        val nextOffset = minOf(offset + CoroutineIoSegmentByteCount, endIndex)
        buffer.write(source, offset, nextOffset)
        write(buffer, (nextOffset - offset).toLong())
        offset = nextOffset
    }
}
