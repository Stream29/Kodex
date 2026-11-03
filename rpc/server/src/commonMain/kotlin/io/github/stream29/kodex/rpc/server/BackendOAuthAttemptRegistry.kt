package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Sanitized terminal outcome; it never retains a callback, authorization URL or credential. */
internal enum class OAuthAttemptOutcome { Committed, Failed, TimedOut, Cancelled, Released, BackendStopped }

/**
 * Owns attempt identities and bounded terminal observations. Callback resources and provider
 * operations are managed by the caller; transitions here linearize acceptance against release.
 */
internal class BackendOAuthAttemptRegistry(
    scope: CoroutineScope,
    private val retention: Duration = 10.minutes,
    private val capacity: Int = 128,
) {
    internal class Attempt internal constructor(val id: Long, val target: OAuthTarget) {
        internal val result = CompletableDeferred<OAuthAttemptOutcome>()
        internal var phase = Phase.Preparing
        internal var worker: Job? = null
        internal var terminal: OAuthAttemptOutcome? = null
    }

    internal enum class Phase { Preparing, Waiting, Accepted, Finished }

    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val workers = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val entries = mutableMapOf<Long, Attempt>()
    private var nextId = 1L
    private var closed = false

    init {
        require(retention.isPositive() && retention.isFinite())
        require(capacity > 0)
    }

    suspend fun reserve(target: OAuthTarget): Attempt = mutex.withLock {
        owner.ensureActive()
        check(!closed) { "OAuth attempt registry is closed." }
        check(entries.size < capacity) { "OAuth attempt capacity is exhausted." }
        check(entries.values.none { it.target == target && it.phase != Phase.Finished }) {
            "This target is already authorizing."
        }
        check(nextId < Long.MAX_VALUE) { "OAuth attempt identities are exhausted." }
        Attempt(nextId++, target).also { entries[it.id] = it }
    }

    suspend fun waiting(attempt: Attempt): Boolean = mutex.withLock {
        if (!isCurrent(attempt) || attempt.phase != Phase.Preparing) return@withLock false
        attempt.phase = Phase.Waiting
        true
    }

    suspend fun attachWorker(attempt: Attempt, worker: Job): Boolean = mutex.withLock {
        if (!isCurrent(attempt) || attempt.phase != Phase.Preparing || attempt.worker != null) return@withLock false
        attempt.worker = worker
        true
    }

    suspend fun accept(attempt: Attempt): Boolean = mutex.withLock {
        if (!isCurrent(attempt) || attempt.phase != Phase.Waiting) return@withLock false
        attempt.phase = Phase.Accepted
        true
    }

    suspend fun finish(attempt: Attempt, outcome: OAuthAttemptOutcome): Boolean = mutex.withLock {
        require(outcome == OAuthAttemptOutcome.Committed || outcome == OAuthAttemptOutcome.Failed ||
            outcome == OAuthAttemptOutcome.TimedOut) { "Invalid OAuth worker outcome." }
        if (!isCurrent(attempt) || attempt.phase == Phase.Finished) return@withLock false
        if (outcome == OAuthAttemptOutcome.Committed && attempt.phase != Phase.Accepted) return@withLock false
        if (outcome == OAuthAttemptOutcome.TimedOut && attempt.phase == Phase.Accepted) return@withLock false
        finishLocked(attempt, outcome)
        true
    }

    /** Returns the exact pending attempt whose resources the caller must release, if any. */
    suspend fun releasePending(id: Long): Attempt? = mutex.withLock {
        val attempt = entries[id] ?: return@withLock null
        if (attempt.phase != Phase.Preparing && attempt.phase != Phase.Waiting) return@withLock null
        finishLocked(attempt, OAuthAttemptOutcome.Released)
        attempt
    }

    /** Explicit cancellation includes accepted work, but never changes a terminal result. */
    suspend fun cancel(id: Long): Attempt? = mutex.withLock {
        val attempt = entries[id] ?: return@withLock null
        if (attempt.phase == Phase.Finished) return@withLock null
        finishLocked(attempt, OAuthAttemptOutcome.Cancelled)
        attempt
    }

    suspend fun cancelTarget(target: OAuthTarget): List<Attempt> = mutex.withLock {
        entries.values.filter { it.target == target && it.phase != Phase.Finished }.onEach {
            finishLocked(it, OAuthAttemptOutcome.Cancelled)
        }
    }

    suspend fun explicitlyCancelled(attempt: Attempt): Boolean = mutex.withLock {
        attempt.terminal == OAuthAttemptOutcome.Cancelled
    }

    suspend fun await(id: Long): OAuthAttemptOutcome {
        val result = mutex.withLock {
            entries[id]?.result ?: error("Unknown or expired OAuth attempt.")
        }
        return result.await()
    }

    suspend fun closeAndJoin() {
        mutex.withLock {
            closed = true
            entries.values.forEach {
                if (it.phase != Phase.Finished) it.result.complete(OAuthAttemptOutcome.BackendStopped)
            }
            entries.clear()
        }
        owner.cancelAndJoin()
    }

    private fun isCurrent(attempt: Attempt): Boolean = entries[attempt.id] === attempt

    private fun finishLocked(attempt: Attempt, outcome: OAuthAttemptOutcome) {
        attempt.phase = Phase.Finished
        attempt.terminal = outcome
        attempt.result.complete(outcome)
        workers.launch {
            delay(retention)
            mutex.withLock {
                if (isCurrent(attempt) && attempt.phase == Phase.Finished) entries.remove(attempt.id)
            }
        }
    }
}
