package io.github.stream29.kodex.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

public actual fun createBackendLifecycle(
    idleTimeout: Duration,
    timeSource: TimeSource,
): BackendLifecycle = BackendLifecycleImpl(idleTimeout, timeSource)

private class BackendLifecycleImpl(
    timeout: Duration,
    timeSource: TimeSource,
) : BackendLifecycle {
    private val admission = Mutex()
    private val origin = timeSource.markNow()
    private val idleTimeout = MutableStateFlow(checkedTimeout(timeout))
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
            idleTimeout.value = checked
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
        val timeout = idleTimeout.value
        if (state.value != BackendPhase.Ready || timeout.isInfinite()) return@withLock false
        if (remainingIdleTime(timeout).isPositive()) return@withLock false
        beginStop(BackendStopReason.IdleTimeout)
        true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun monitorIdleExpiry() {
        combine(state, idleTimeout) { phase, timeout -> phase to timeout }
            .transformLatest { (phase, timeout) ->
                when (phase) {
                    BackendPhase.Starting -> Unit
                    BackendPhase.Stopping, BackendPhase.Stopped -> emit(Unit)
                    BackendPhase.Ready -> {
                        if (timeout.isInfinite()) return@transformLatest
                        while (true) {
                            val remaining = admission.withLock {
                                currentCoroutineContext().ensureActive()
                                if (state.value != BackendPhase.Ready || idleTimeout.value != timeout) {
                                    return@transformLatest
                                }
                                remainingIdleTime(timeout).also {
                                    if (!it.isPositive()) beginStop(BackendStopReason.IdleTimeout)
                                }
                            }
                            if (!remaining.isPositive()) return@transformLatest
                            // Renewals only move the timestamp; recheck it at the original wake time.
                            delay(remaining)
                        }
                    }
                }
            }.first()
    }

    private fun remainingIdleTime(timeout: Duration): Duration = timeout - (origin.elapsedNow() - lastRenewal)

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
