package io.github.stream29.kodex.utils.kotlinxiocoroutines

/**
 * Executes [block] with this resource and returns its result.
 *
 * On normal completion, failure, cancellation, or a non-local return, attempts [CoroutineCloseable.close]
 * once in a non-cancellable context. Closing may suspend and is not guaranteed to succeed.
 * If both [block] and closing fail, the original block failure is rethrown with the close failure
 * suppressed. If only closing fails, its failure is thrown.
 */
public expect suspend inline fun <T : CoroutineCloseable, R> T.use(block: suspend (T) -> R): R

public expect suspend fun CoroutineRawSource.copyTo(
    sink: CoroutineRawSink,
    byteCount: Long = Long.MAX_VALUE,
): Long
