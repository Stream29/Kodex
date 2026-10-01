package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Stateful owner for direct child processes using ordinary byte pipes.
 *
 * A client created by [CoroutineScope.ProcessClient] is a child of that scope.
 * Closing it terminates every process session that it still owns.
 */
public expect class ProcessClient internal constructor(
    scope: CoroutineScope,
) : CoroutineScope, AutoCloseable {
    override val coroutineContext: CoroutineContext

    /** Starts [command] without inserting a shell between the caller and child process. */
    public suspend fun start(command: ProcessCommand): ProcessSession

    override fun close()
}

/** Creates an independently cancellable direct-process client under this scope. */
public fun CoroutineScope.ProcessClient(): ProcessClient {
    return ProcessClient(supervisorChildScope())
}

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Process client is closed.")
}

internal fun CoroutineScope.lazyProcessCancellationGuard(
    close: () -> Unit,
): Job = launch(start = CoroutineStart.LAZY) {
    try {
        awaitCancellation()
    } finally {
        close()
    }
}
