package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sanitized terminal outcome; it never retains a callback, authorization URL or credential. */
internal enum class OAuthAttemptOutcome { Committed, Failed, TimedOut, Cancelled, Released, BackendStopped }

/**
 * Indexes live attempts only. Completion belongs to the operation, not to the registry.
 * A finishing attempt still occupies its target until its worker releases its resources.
 */
internal class BackendOAuthAttemptRegistry(
    private val scope: CoroutineScope,
    private val capacity: Int = 128,
) {
    internal class Attempt internal constructor(val id: Long, val target: OAuthTarget) {
        internal val result = CompletableDeferred<OAuthAttemptOutcome>()
        internal var phase = Phase.Preparing
        internal var worker: Job? = null
        internal var terminal: OAuthAttemptOutcome? = null
    }

    internal enum class Phase { Preparing, Waiting, Accepted, Cleaning }
    private val mutex = Mutex()
    private val entries = mutableMapOf<Long, Attempt>()
    private var nextId = 1L
    private var closed = false

    init {
        require(capacity > 0)
    }

    suspend fun reserve(target: OAuthTarget): Attempt = mutex.withLock {
        scope.coroutineContext.ensureActive()
        check(!closed) { "OAuth attempt registry is closed." }
        check(entries.size < capacity) { "OAuth attempt capacity is exhausted." }
        check(entries.values.none { it.target == target }) {
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
        if (!isCurrent(attempt) || attempt.phase == Phase.Cleaning) return@withLock false
        if (outcome == OAuthAttemptOutcome.Committed && attempt.phase != Phase.Accepted) return@withLock false
        if (outcome == OAuthAttemptOutcome.TimedOut && attempt.phase == Phase.Accepted) return@withLock false
        finishLocked(attempt, outcome)
        true
    }

    suspend fun releasePending(attempt: Attempt): Boolean = mutex.withLock {
        if (!isCurrent(attempt) || (attempt.phase != Phase.Preparing && attempt.phase != Phase.Waiting)) {
            return@withLock false
        }
        finishLocked(attempt, OAuthAttemptOutcome.Released)
        true
    }

    /** Explicit cancellation includes accepted work, but never changes a terminal result. */
    suspend fun cancel(id: Long): Attempt? = mutex.withLock {
        val attempt = entries[id] ?: return@withLock null
        if (attempt.phase == Phase.Cleaning) return@withLock null
        finishLocked(attempt, OAuthAttemptOutcome.Cancelled)
        attempt
    }

    suspend fun cancelTarget(target: OAuthTarget): List<Attempt> = mutex.withLock {
        entries.values.filter { it.target == target && it.phase != Phase.Cleaning }.onEach {
            finishLocked(it, OAuthAttemptOutcome.Cancelled)
        }
    }

    suspend fun explicitlyCancelled(attempt: Attempt): Boolean = mutex.withLock {
        attempt.terminal == OAuthAttemptOutcome.Cancelled
    }

    suspend fun retire(attempt: Attempt) {
        mutex.withLock {
            if (isCurrent(attempt)) {
                attempt.result.complete(OAuthAttemptOutcome.BackendStopped)
                entries.remove(attempt.id)
                attempt.worker = null
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            closed = true
            entries.values.forEach { it.result.complete(OAuthAttemptOutcome.BackendStopped) }
            entries.clear()
        }
    }

    private fun isCurrent(attempt: Attempt): Boolean = entries[attempt.id] === attempt

    private fun finishLocked(attempt: Attempt, outcome: OAuthAttemptOutcome) {
        attempt.phase = Phase.Cleaning
        attempt.terminal = outcome
        attempt.result.complete(outcome)
    }
}
