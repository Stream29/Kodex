package io.github.stream29.kodex.utils.shellclient

import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal expect class PlatformShellClient(
    scope: CoroutineScope,
) : ShellClient {
    override val coroutineContext: CoroutineContext

    /** Starts [command] in a session owned by this client. */
    override suspend fun start(command: ShellProcessCommand): ProcessSession

    override fun close()
}

/**
 * Creates an independently cancellable shell client under this scope.
 * @throws IllegalArgumentException when this scope has no parent Job.
 */
public fun CoroutineScope.ShellClient(): ShellClient {
    return PlatformShellClient(supervisorChildScope())
}

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Shell client is closed.")
}

internal suspend fun CoroutineScope.acquireShellSession(
    dispatcher: CoroutineDispatcher,
    acquire: suspend () -> ProcessSession,
): ProcessSession {
    requireOpen()
    val ownerJob = requireNotNull(coroutineContext[Job])
    var acquired: ProcessSession? = null
    var handedOff = false
    var primary: Throwable? = null
    val acquisitionDone = CompletableDeferred<Unit>()
    val acquisitionGuard = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                acquisitionDone.await()
                if (!handedOff) {
                    acquired?.let { session ->
                        session.scope.cancel(primary as? CancellationException
                            ?: CancellationException("Shell startup failed."))
                        session.scope.coroutineContext[Job]!!.join()
                    }
                }
            }
        }
    }
    try {
        ownerJob.ensureActive()
        val session = withContext(dispatcher) { acquire().also { acquired = it } }
        ownerJob.ensureActive()
        handedOff = true
        return session
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        acquisitionDone.complete(Unit)
        acquisitionGuard.cancel()
        if (!handedOff) {
            withContext(NonCancellable) {
                acquisitionGuard.join()
            }
        }
    }
}
