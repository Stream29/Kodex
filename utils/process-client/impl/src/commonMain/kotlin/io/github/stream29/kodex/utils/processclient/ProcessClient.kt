package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

internal expect class PlatformProcessClient(
    scope: CoroutineScope,
) : ProcessClient {
    override val coroutineContext: CoroutineContext

    /** Starts [command] without inserting a shell between the caller and child process. */
    override suspend fun start(command: ProcessCommand): ProcessSession

    override fun close()
}

/**
 * Creates an independently cancellable direct-process client under this scope.
 * @throws IllegalArgumentException when this scope has no parent Job.
 */
public fun CoroutineScope.ProcessClient(): ProcessClient {
    return PlatformProcessClient(supervisorChildScope())
}

internal fun CoroutineScope.requireOpen() {
    if (!isActive) throw ProcessException("Process client is closed.")
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
internal fun CoroutineScope.launchProcessCancellationGuard(
    dispatcher: CoroutineDispatcher,
    close: () -> Unit,
): Job = launch(dispatcher, start = CoroutineStart.ATOMIC) {
    try {
        awaitCancellation()
    } finally {
        close()
    }
}

// Capture before the cancellable dispatcher return, so an unclaimed resource
// is closed even when its long-lived client remains active.
internal suspend fun CoroutineScope.acquireProcessSession(
    dispatcher: CoroutineDispatcher,
    acquire: () -> ProcessSession,
): ProcessSession {
    requireOpen()
    val ownerJob = requireNotNull(coroutineContext[Job])
    var acquired: ProcessSession? = null
    var handedOff = false
    var primary: Throwable? = null
    val acquisitionDone = CompletableDeferred<Unit>()
    val releaseResult = CompletableDeferred<Result<Unit>>()
    // Register before entering the OS acquisition. Even owner cancellation
    // during spawn must wait for publication/rollback before join completes.
    val acquisitionGuard = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + dispatcher) {
                acquisitionDone.await()
                var cleanupFailure: Throwable? = null
                if (!handedOff) {
                    try {
                        acquired?.closeAndJoin()
                    } catch (cleanup: Throwable) {
                        cleanupFailure = cleanup
                    }
                    try {
                        acquired?.let { withTimeout(6.seconds) { it.exitCode.await() } }
                    } catch (cleanup: Throwable) {
                        val first = cleanupFailure
                        if (first == null) cleanupFailure = cleanup
                        else if (first !== cleanup) first.addSuppressed(cleanup)
                    }
                }
                val failure = cleanupFailure
                releaseResult.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
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
                releaseResult.await().exceptionOrNull()?.let { cleanup ->
                    val original = primary
                    if (original == null) throw cleanup
                    if (cleanup !== original) original.addSuppressed(cleanup)
                }
            }
        }
    }
}
