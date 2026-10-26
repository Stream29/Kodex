package io.github.stream29.kodex.backend

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Creates the host's one lifecycle authority; no process, RPC service or background task is started.
 *
 * @throws IllegalArgumentException if [idleTimeout] is zero or negative.
 */
public expect fun createBackendLifecycle(
    idleTimeout: Duration = 5.minutes,
    timeSource: TimeSource = TimeSource.Monotonic,
): BackendLifecycle

/**
 * Host-owned expiry observer. Cancellation stops this observer only, not accepted shutdown.
 * The host should launch exactly one observer and cancel it during its resource cleanup.
 *
 * @throws IllegalArgumentException if [pollInterval] is nonpositive or infinite.
 */
public expect suspend fun BackendLifecycle.monitorIdleExpiry(pollInterval: Duration = 1.seconds)
