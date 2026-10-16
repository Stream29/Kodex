package io.github.stream29.kodex.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Creates the host's one lifecycle authority; no process, RPC service or background task is started.
 *
 * @throws IllegalArgumentException if [idleTimeout] is zero or negative.
 */
public fun createBackendLifecycle(
    idleTimeout: Duration = 5.minutes,
    timeSource: TimeSource = TimeSource.Monotonic,
): BackendLifecycle = BackendLifecycleImpl(idleTimeout, timeSource)

/**
 * Host-owned expiry observer. Cancellation stops this observer only, not accepted shutdown.
 * The host should launch exactly one observer and cancel it during its resource cleanup.
 *
 * @throws IllegalArgumentException if [pollInterval] is nonpositive or infinite.
 */
public suspend fun BackendLifecycle.monitorIdleExpiry(pollInterval: Duration = 1.seconds) {
    require(pollInterval.isPositive() && pollInterval.isFinite()) {
        "The expiry poll interval must be finite and positive."
    }
    while (state.value == BackendPhase.Starting || state.value == BackendPhase.Ready) {
        if (expireIfIdle()) return
        delay(pollInterval)
    }
}

private class BackendLifecycleImpl(
    timeout: Duration,
    timeSource: TimeSource,
) : BackendLifecycle {
    private val admission = Mutex()
    private val origin = timeSource.markNow()
    private var idleTimeout = checkedTimeout(timeout)
    private var lastRenewal = Duration.ZERO
    private val mutableState = MutableStateFlow(BackendPhase.Starting)
    private val stop = CompletableDeferred<BackendStopReason>()
    override val state = mutableState.asStateFlow()

    override suspend fun markReady(): Boolean = admission.withLock {
        currentCoroutineContext().ensureActive()
        if (state.value != BackendPhase.Starting) return@withLock false
        lastRenewal = origin.elapsedNow()
        mutableState.value = BackendPhase.Ready
        true
    }

    override suspend fun renew(): Boolean = admission.withLock {
        currentCoroutineContext().ensureActive()
        if (state.value != BackendPhase.Ready) return@withLock false
        lastRenewal = origin.elapsedNow()
        true
    }

    override suspend fun updateIdleTimeout(timeout: Duration) {
        val checked = checkedTimeout(timeout)
        admission.withLock {
            currentCoroutineContext().ensureActive()
            idleTimeout = checked
        }
    }

    override suspend fun requestStop(): BackendStopAcceptance = admission.withLock {
        currentCoroutineContext().ensureActive()
        when (state.value) {
            BackendPhase.Starting, BackendPhase.Ready -> {
                beginStop(BackendStopReason.Manual)
                BackendStopAcceptance.Accepted
            }
            BackendPhase.Stopping -> BackendStopAcceptance.AlreadyStopping
            BackendPhase.Stopped -> BackendStopAcceptance.AlreadyStopped
        }
    }

    override suspend fun expireIfIdle(): Boolean = admission.withLock {
        currentCoroutineContext().ensureActive()
        if (state.value != BackendPhase.Ready || idleTimeout.isInfinite()) return@withLock false
        if (origin.elapsedNow() - lastRenewal < idleTimeout) return@withLock false
        beginStop(BackendStopReason.IdleTimeout)
        true
    }

    override suspend fun awaitStop(): BackendStopReason {
        currentCoroutineContext().ensureActive()
        return stop.await()
    }

    override suspend fun finishStop(): Boolean = admission.withLock {
        currentCoroutineContext().ensureActive()
        when (state.value) {
            BackendPhase.Starting, BackendPhase.Ready -> error("Backend cleanup cannot finish before stopping.")
            BackendPhase.Stopping -> {
                mutableState.value = BackendPhase.Stopped
                true
            }
            BackendPhase.Stopped -> false
        }
    }

    private fun beginStop(reason: BackendStopReason) {
        mutableState.value = BackendPhase.Stopping
        stop.complete(reason)
    }
}

private fun checkedTimeout(timeout: Duration): Duration {
    require(timeout.isPositive()) { "Backend idle TTL must be positive or infinite." }
    return timeout
}
