package io.github.stream29.kodex.utils.kotlinxiocoroutines

import io.github.stream29.kodex.utils.coroutines.use as useCloseable
import kotlinx.io.Buffer

public actual suspend inline fun <T : CoroutineCloseable, R> T.use(block: suspend (T) -> R): R =
    useCloseable(block)

public actual suspend fun CoroutineRawSource.copyTo(
    sink: CoroutineRawSink,
    byteCount: Long,
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
