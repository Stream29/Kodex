package io.github.stream29.kodex.cli.app

import kotlinx.coroutines.CancellationException

internal fun runCliSafely(
    backendStart: Boolean,
    processId: () -> Long,
    reportFailure: (Throwable) -> Unit,
    output: (String) -> Unit,
    execute: () -> Int,
): Int = try {
    execute()
} catch (_: CancellationException) {
    130
} catch (failure: Throwable) {
    try {
        reportFailure(failure)
    } catch (_: Throwable) {
        // The original terminal diagnostic and status must survive a broken logger.
    }
    val prefix = if (backendStart) "Unable to run Kodex [pid=${processId()}]: "
        else "Unable to run Kodex: "
    output(prefix + (failure.message ?: "operation failed"))
    1
}
