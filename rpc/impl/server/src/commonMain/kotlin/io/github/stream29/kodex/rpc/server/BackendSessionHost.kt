package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Owns one lazily acquired repository and its accepted work. The factory runs
 * only on actual [BackendSessionHost.repository] access and must create the
 * repository in its receiver scope; an existing frontend-owned instance is
 * not a valid factory result. Neither the host nor its bindings may escape.
 *
 * Normal return, failure and cancellation wait for repository/Session cleanup.
 * A cancelled RPC waiter does not cancel work already accepted by this host.
 */
public suspend fun <R> withBackendSessionHost(
    createRepository: suspend CoroutineScope.() -> KodexRootSessionRepository,
    block: suspend CoroutineScope.(BackendSessionHost) -> R,
): R = withBackendSessionHost(TimeSource.Monotonic, createRepository, block)

internal suspend fun <R> withBackendSessionHost(
    timeSource: TimeSource,
    createRepository: suspend CoroutineScope.() -> KodexRootSessionRepository,
    block: suspend CoroutineScope.(BackendSessionHost) -> R,
): R = coroutineScope {
    val owner = supervisorChildScope()
    val backgroundFailure = CompletableDeferred<Throwable>()
    val monitor = launch { throw backgroundFailure.await() }
    var host: BackendSessionHost? = null
    var failure: Throwable? = null
    try {
        host = BackendSessionHost(owner, createRepository, timeSource.markNow(), backgroundFailure)
        coroutineScope { block(host) }
    } catch (cause: Throwable) {
        failure = cause
        throw cause
    } finally {
        monitor.cancel()
        try {
            withContext(NonCancellable) {
                owner.cancelAndJoin()
                host?.completeDeactivation()
                monitor.join()
            }
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup
            failure.addSuppressed(cleanup)
        }
    }
}

/**
 * Backend-only resource; no Job, repository or binding crosses the RPC boundary.
 * @property acquiredRepository Null until successful on-demand acquisition;
 * failed/cancelled attempts leave it absent, rather than publishing a dead owner.
 */
public class BackendSessionHost internal constructor(
    private val owner: CoroutineScope,
    private val createRepository: suspend CoroutineScope.() -> KodexRootSessionRepository,
    private val origin: TimeMark,
    private val backgroundFailure: CompletableDeferred<Throwable>,
) {
    private val lifecycle = Mutex()
    private val acquisition = Mutex()
    private var acquiredRepository: KodexRootSessionRepository? = null

    /**
     * Acquires the actual repository once under the original backend owner.
     * Concurrent first accesses share one successful factory result; failure or
     * cancellation never publishes a partial/dead owner. A factory is responsible
     * for cleaning its failed acquisition. Accepted work outlives an RPC waiter.
     * Closing an untouched host never invokes the factory.
     */
    public suspend fun repository(): KodexRootSessionRepository = inBackend {
        acquisition.withLock {
            owner.ensureActive()
            acquiredRepository ?: owner.createRepository().also { repository ->
                currentCoroutineContext().ensureActive()
                owner.ensureActive()
                repository.coroutineContext.ensureActive()
                acquiredRepository = repository
            }
        }
    }

    // Renewal metadata, not a second catalog/active flag. Session identity and
    // liveness remain the original repository instance and its actual Job.
    private val renewals = mutableMapOf<Int, Renewal>()

    /** Explicit activation; a successful return grants a full TTL after loading. */
    public suspend fun keepSessionAlive(index: Int): Unit = inBackend {
        lifecycle.withLock {
            owner.ensureActive()
            val repository = repository()
            if (index !in repository.entries.value) throw SessionNotFound()
            val previous = renewals[index]
            if (previous != null && !previous.binding.session.coroutineContext.job.isActive) {
                closeLocked(index, previous)
            }
            val session = repository.open(index)
            val renewal = renewals[index]?.also {
                check(it.binding.session === session) { "Repository ownership changed outside the backend host." }
            } ?: install(index, session)
            renewLocked(renewal)
        }
    }

    /** Resolves a live binding without loading or extending its lifetime. */
    public suspend fun session(index: Int): BackendSessionBinding = lifecycle.withLock {
        activeLocked(index).binding
    }

    /**
     * Accepts work into the Session scope, retaining its complete result/wait
     * semantics. Only a runningTurn renews residency; other work can be
     * cancelled by TTL expiry. The block must not re-enter host lifecycle APIs
     * before its first suspension.
     */
    public suspend fun <R> inSession(
        index: Int,
        block: suspend KodexAgentSession.() -> R,
    ): R = inBackend {
        val work = lifecycle.withLock {
            val renewal = activeLocked(index)
            val session = renewal.binding.session
            val previousTurn = session.runtime.runningTurn.value
            renewal.binding.operations.async(start = CoroutineStart.UNDISPATCHED) { session.block() }.also {
                // Observe the slot before releasing the lifecycle boundary, so
                // expiry cannot beat the asynchronous StateFlow collector.
                val currentTurn = session.runtime.runningTurn.value
                if (currentTurn != null && currentTurn !== previousTurn) renewLocked(renewal)
            }
        }
        work.await()
    }

    /** Global accepted work belongs to the host, not its RPC waiter. */
    public suspend fun <R> inBackend(block: suspend () -> R): R {
        currentCoroutineContext().ensureActive()
        owner.ensureActive()
        return owner.async { block() }.await()
    }

    /** Coordinates delete with activation/expiry; missing entries remain false. */
    public suspend fun deleteSession(index: Int): Boolean = inBackend {
        lifecycle.withLock {
            owner.ensureActive()
            val repository = repository()
            if (index !in repository.entries.value) return@withLock false
            renewals[index]?.let { closeLocked(index, it) }
            repository.delete(index)
            true
        }
    }

    /**
     * Captures a management source while activation/deletion/expiry are excluded.
     * Full fork may temporarily open an inactive source; a nonce-bound operation
     * must use its existing live owner. Temporary owners are never given a lease.
     * The block must not call another host lifecycle method.
     */
    internal suspend fun <T> withManagementSource(
        index: Int,
        requireActive: Boolean,
        block: suspend (KodexAgentSession) -> T,
    ): T = inBackend {
        lifecycle.withLock {
            val repository = repository()
            if (index !in repository.entries.value) throw SessionNotFound()
            val renewal = renewals[index]
            val active = renewal?.binding?.session?.takeIf { it.coroutineContext.job.isActive }
            if (requireActive && active == null) throw SessionNotActive()
            if (active != null) block(active)
            else {
                if (renewal != null) closeLocked(index, renewal)
                val temporary = repository.open(index)
                try {
                    block(temporary)
                } finally {
                    withContext(NonCancellable) { temporary.cancelAndJoin() }
                }
            }
        }
    }

    private fun activeLocked(index: Int): Renewal {
        owner.ensureActive()
        val renewal = renewals[index] ?: throw SessionNotActive()
        if (!renewal.binding.session.coroutineContext.job.isActive) throw SessionNotActive()
        return renewal
    }

    private fun renewLocked(renewal: Renewal) {
        renewal.deadline = maxOf(renewal.deadline, origin.elapsedNow() + SessionTtl)
    }

    private fun install(index: Int, session: KodexAgentSession): Renewal {
        val renewal = Renewal(BackendSessionBinding(session), origin.elapsedNow() + SessionTtl)
        renewals[index] = renewal
        renewal.renewalJob = background {
            session.runtime.runningTurn.collectLatest { turn ->
                if (turn != null) {
                    while (true) {
                        val renewed = lifecycle.withLock {
                            if (renewals[index] !== renewal ||
                                !session.coroutineContext.job.isActive ||
                                session.runtime.runningTurn.value !== turn
                            ) false else {
                                // A cancelling turn still owns its slot while cleaning up.
                                renewLocked(renewal)
                                true
                            }
                        }
                        if (!renewed) return@collectLatest
                        delay(RenewalInterval)
                    }
                }
            }
        }
        renewal.expiryJob = background {
            while (true) {
                val remaining = lifecycle.withLock {
                    if (renewals[index] !== renewal) return@background
                    renewal.deadline - origin.elapsedNow()
                }
                if (remaining.isPositive()) delay(remaining)
                val expired = lifecycle.withLock {
                    if (renewals[index] !== renewal) return@background
                    if (renewal.deadline > origin.elapsedNow()) false else {
                        closeLocked(index, renewal)
                        true
                    }
                }
                if (expired) return@background
            }
        }
        renewal.completionJob = background {
            session.coroutineContext.job.join()
            renewal.binding.deactivate()
        }
        return renewal
    }

    private suspend fun closeLocked(index: Int, renewal: Renewal) {
        val caller = currentCoroutineContext().job
        withContext(NonCancellable) {
            renewal.renewalJob.cancelAndJoin()
            if (renewal.expiryJob !== caller) renewal.expiryJob.cancelAndJoin()
            // Let accepted operations restore their state while the original
            // storage owner is still live. This is cleanup, not extra residency.
            renewal.binding.operations.cancelAndJoin()
            renewal.binding.session.cancelAndJoin()
            renewal.completionJob.cancelAndJoin()
            renewal.binding.deactivate()
            if (renewals[index] === renewal) renewals.remove(index)
        }
    }

    private fun background(block: suspend () -> Unit): Job = owner.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            backgroundFailure.complete(failure)
        }
    }

    internal fun completeDeactivation() {
        renewals.values.forEach { it.binding.deactivate() }
        renewals.clear()
    }
}

/** A binding is tied to one owner, never silently rebound after reactivation. */
public class BackendSessionBinding internal constructor(public val session: KodexAgentSession) {
    internal val operations: CoroutineScope = session.supervisorChildScope()
    private val deactivated = CompletableDeferred<Unit>()
    public val inactive: Deferred<Unit> get() = deactivated

    /** Ends existing RPC upstreams on deactivation without keeping the owner alive. */
    public fun <T> observe(source: Flow<T>): Flow<T> = channelFlow {
        if (deactivated.isCompleted || !session.coroutineContext.job.isActive) throw SessionNotActive()
        val watcher = launch {
            deactivated.await()
            throw SessionNotActive()
        }
        try {
            source.collect { send(it) }
        } finally {
            watcher.cancel()
        }
    }

    internal fun deactivate() {
        deactivated.complete(Unit)
    }
}

private class Renewal(val binding: BackendSessionBinding, var deadline: Duration) {
    lateinit var renewalJob: Job
    lateinit var expiryJob: Job
    lateinit var completionJob: Job
}

private val SessionTtl = 60.seconds
private val RenewalInterval = 20.seconds
