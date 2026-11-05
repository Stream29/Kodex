package io.github.stream29.kodex.utils.coroutines

/**
 * Coroutine-friendly close boundary for resources whose close operation can suspend.
 */
public fun interface CoroutineCloseable {
    public suspend fun close()
}

/**
 * Executes [block] with this resource and returns its result.
 *
 * On normal completion, failure, cancellation, or a non-local return, attempts [CoroutineCloseable.close]
 * once in a non-cancellable context. If both [block] and closing fail, the original block failure is
 * rethrown with the close failure suppressed. If only closing fails, its failure is thrown.
 */
public expect suspend inline fun <T : CoroutineCloseable, R> T.use(
    block: suspend (T) -> R,
): R

/**
 * Executes [block] with this resource and returns its result.
 *
 * If [block] fails or is cancelled, attempts [close] once in a non-cancellable context and attaches
 * any close failure as suppressed on the original failure before rethrowing.
 */
public expect suspend inline fun <T, R> T.closeOnFailure(
    crossinline close: suspend (T) -> Unit,
    block: suspend (T) -> R,
): R

/**
 * Executes [block] with this resource and returns its result.
 *
 * If [block] fails or is cancelled, attempts [CoroutineCloseable.close] once in a non-cancellable
 * context and attaches any close failure as suppressed on the original failure before rethrowing.
 */
public expect suspend inline fun <T : CoroutineCloseable, R> T.closeOnFailure(
    block: suspend (T) -> R,
): R

/**
 * Executes [block] with this resource and returns its result.
 *
 * If [block] fails or is cancelled, attempts [AutoCloseable.close] once and attaches any close
 * failure as suppressed on the original failure before rethrowing.
 */
public expect inline fun <T : AutoCloseable, R> T.closeOnFailure(
    block: (T) -> R,
): R
