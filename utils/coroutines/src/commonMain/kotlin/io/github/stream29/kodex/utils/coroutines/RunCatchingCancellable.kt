package io.github.stream29.kodex.utils.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and captures ordinary failures without consuming coroutine
 * cancellation.
 */
public inline fun <Value> runCatchingCancellable(block: () -> Value): Result<Value> =
    runCatching(block).onFailure { failure ->
        if (failure is CancellationException) throw failure
    }
