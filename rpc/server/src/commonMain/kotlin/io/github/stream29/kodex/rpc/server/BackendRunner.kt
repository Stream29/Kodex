package io.github.stream29.kodex.rpc.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Report the first fatal exit to the graph, not to the supervisor's uncaught handler. */
internal fun CoroutineScope.launchBackendRunner(
    name: String,
    isStopping: () -> Boolean,
    fatal: CompletableDeferred<Throwable>,
    block: suspend CoroutineScope.() -> Unit,
): Job = async(CoroutineName(name), block = block).also { runner ->
    runner.invokeOnCompletion { cause ->
        if (!isStopping() && cause !is CancellationException) {
            fatal.complete(cause ?: IllegalStateException("The $name runner ended unexpectedly."))
        }
    }
}

internal suspend fun runBackendRunnerLoop(
    isStopping: () -> Boolean,
    reportRetry: (Exception) -> Unit,
    iteration: suspend () -> Unit,
) {
    var backoff = 200L
    while (!isStopping()) {
        currentCoroutineContext().ensureActive()
        try {
            iteration()
            backoff = 200L
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (retry: Exception) {
            currentCoroutineContext().ensureActive()
            if (isStopping()) return
            try {
                reportRetry(retry)
            } catch (reportFailure: Throwable) {
                if (reportFailure !== retry) reportFailure.addSuppressed(retry)
                throw reportFailure
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(2_000L)
        }
    }
}
