package io.github.stream29.kodex.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** Backend-host phases, not frontend connection or Session residency states. */
public enum class BackendPhase {
    Starting,
    Ready,
    Stopping,
    Stopped,
}

public enum class BackendStopReason {
    Manual,
    IdleTimeout,
}

/** Acceptance describes a stop request, never completion of resource cleanup. */
public enum class BackendStopAcceptance {
    Accepted,
    AlreadyStopping,
    AlreadyStopped,
}

/**
 * Backend-only admission and idle-lifetime authority, independent of its RPC connections.
 *
 * This object does not own the service graph, process or instance lock. Its host observes
 * [awaitStop], cooperatively cancels and cleans those resources, and only then calls [finishStop].
 * A stop request must not cancel the management RPC waiter before its acceptance can be delivered;
 * the transport/host coordinates that delivery separately.
 *
 * Ready -> Stopping is irreversible and serialized against renewal and expiry. Only accepted
 * frontend heartbeats and running-turn work should call [renew]. Local process existence,
 * pending user interaction, notifications and ordinary settings reads do not imply renewal.
 * Duration.INFINITE represents the configured TTL=-1; finite TTLs must be strictly positive.
 * Suspending operations are cancellable, but cancelling a stop waiter never retracts shutdown.
 */
public interface BackendLifecycle {
    /** Phase observation only; [awaitStop] retains the first accepted reason. */
    public val state: StateFlow<BackendPhase>

    /**
     * Marks completed startup as Ready and grants a full idle interval.
     * Returns false if startup already completed or shutdown has been requested.
     *
     * @throws CancellationException if this caller is cancelled before admission.
     */
    public suspend fun markReady(): Boolean

    /**
     * Renews the monotonic idle deadline only in Ready. Returns false otherwise.
     * Rejected renewals cannot undo a stop or prolong an unavailable Starting backend.
     *
     * @throws CancellationException if this caller is cancelled before admission.
     */
    public suspend fun renew(): Boolean

    /**
     * Changes the idle interval without granting a new renewal. A shorter interval can make
     * the next expiry check immediately eligible; an infinite interval disables idle expiry.
     * Shutdown already accepted is unaffected.
     *
     * @throws IllegalArgumentException if [timeout] is zero or negative.
     * @throws CancellationException if this caller is cancelled before admission.
     */
    public suspend fun updateIdleTimeout(timeout: Duration)

    /**
     * Accepts irreversible manual shutdown, including during startup, without waiting for cleanup.
     * Repeated requests do not alter the first stop reason or reset the phase.
     *
     * @throws CancellationException if this caller is cancelled before admission.
     */
    public suspend fun requestStop(): BackendStopAcceptance

    /**
     * Atomically checks expiry and accepts idle shutdown in Ready. Returns true only for the
     * winning transition; Starting, already stopping, and infinite TTL cannot expire.
     *
     * @throws CancellationException if this caller is cancelled before admission.
     */
    public suspend fun expireIfIdle(): Boolean

    /**
     * Waits for the first accepted stop reason, not for resources to close.
     * The stop remains accepted even if any or all callers cancel this wait.
     *
     * @throws CancellationException if this waiter is cancelled.
     */
    public suspend fun awaitStop(): BackendStopReason

    /**
     * Publishes Stopped after the host has completed its owned resource cleanup.
     * Returns false when already Stopped. This cannot skip the Stopping phase.
     *
     * @throws IllegalStateException if shutdown has not been requested.
     * @throws CancellationException if this caller is cancelled before admission.
     */
    public suspend fun finishStop(): Boolean
}
