package io.github.stream29.kodex.utils.coroutines

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

public actual suspend inline fun <T : CoroutineCloseable, R> T.use(
    block: suspend (T) -> R,
): R {
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
            if (originalFailure !== closeFailure) {
                originalFailure.addSuppressed(closeFailure)
            }
        }
    }
}

public actual suspend inline fun <T, R> T.closeOnFailure(
    crossinline close: suspend (T) -> Unit,
    block: suspend (T) -> R,
): R =
    try {
        block(this)
    } catch (failure: Throwable) {
        try {
            withContext(NonCancellable) { close(this@closeOnFailure) }
        } catch (closeFailure: Throwable) {
            if (failure !== closeFailure) {
                failure.addSuppressed(closeFailure)
            }
        }
        throw failure
    }

public actual suspend inline fun <T : CoroutineCloseable, R> T.closeOnFailure(
    block: suspend (T) -> R,
): R = closeOnFailure(CoroutineCloseable::close, block)

public actual inline fun <T : AutoCloseable, R> T.closeOnFailure(
    block: (T) -> R,
): R =
    try {
        block(this)
    } catch (failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (failure !== closeFailure) {
                failure.addSuppressed(closeFailure)
            }
        }
        throw failure
    }
