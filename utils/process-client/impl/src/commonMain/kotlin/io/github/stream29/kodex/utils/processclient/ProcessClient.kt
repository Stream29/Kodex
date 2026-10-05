package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

internal expect class PlatformProcessClient(
    scope: CoroutineScope,
) : ProcessClient {
    override val coroutineContext: CoroutineContext

    /** Starts [command] without inserting a shell between the caller and child process. */
    override suspend fun start(command: ProcessCommand): ProcessSession

    override fun close()
}

/** Creates an independently cancellable direct-process client under this scope. */
public fun CoroutineScope.ProcessClient(): ProcessClient {
    return PlatformProcessClient(supervisorChildScope())
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
