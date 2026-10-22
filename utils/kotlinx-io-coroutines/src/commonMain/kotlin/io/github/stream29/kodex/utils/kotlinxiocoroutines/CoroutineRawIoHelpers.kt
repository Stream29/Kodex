package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer

public actual suspend inline fun <T : CoroutineCloseable, R> T.use(block: suspend (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (throwable: Throwable) {
        failure = throwable
        throw throwable
    } finally {
        try {
            withContext(NonCancellable) { close() }
        } catch (closeFailure: Throwable) {
            val originalFailure = failure
            if (originalFailure == null) {
                throw closeFailure
            }
            originalFailure.addSuppressed(closeFailure)
        }
    }
}

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
