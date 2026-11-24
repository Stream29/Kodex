package io.github.stream29.kodex.backend

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
