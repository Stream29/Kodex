package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.oauthloopback.OAuthCallbackValuePolicy
import io.github.stream29.kodex.utils.oauthloopback.OAuthLoopbackListener
import io.github.stream29.kodex.utils.oauthloopback.openOAuthLoopbackListener
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Backend-owned callback and observation lifecycle. Provider exchange and persistence remain in
 * the existing backend OAuth engine; this adapter owns only listener resources and result handles.
 */
internal class BackendOwnedOAuth(
    scope: CoroutineScope,
    private val candidates: (OAuthTarget) -> List<String>,
    private val begin: suspend (OAuthTarget, String, (Long) -> Unit) -> BackendOAuthPreparation,
    private val waitingState: suspend (Long) -> String,
    private val holdPendingDeadline: suspend (Long) -> Boolean,
    private val complete: suspend (Long, String) -> Unit,
    private val cancelInner: suspend (Long) -> Unit,
    private val releaseInner: suspend (Long) -> Unit,
    private val openListener: suspend (CoroutineScope, List<String>) -> OAuthLoopbackListener = ::openOAuthLoopbackListener,
    private val callbackDeadline: Duration = 10.minutes,
    retention: Duration = 10.minutes,
    capacity: Int = 128,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val reportCleanupFailure: () -> Unit = {},
) {
    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(requireNotNull(scope.coroutineContext[Job])))
    private val attempts = BackendOAuthAttemptRegistry(owner, retention, capacity)

    init { require(callbackDeadline.isPositive() && callbackDeadline.isFinite()) }

    suspend fun start(target: OAuthTarget): OAuthAuthorization = accepted {
        val slot = attempts.reserve(target)
        val reservedAt = timeSource.markNow()
        val ready = Channel<OAuthAuthorization>(capacity = 1)
        val worker = owner.launch(start = CoroutineStart.LAZY) {
            runAttempt(slot, ready, reservedAt.elapsedNow())
        }
        worker.invokeOnCompletion {
            ready.close(IllegalStateException("Authorization could not be prepared."))
        }
        if (!attempts.attachWorker(slot, worker)) {
            worker.cancel()
            error("Authorization could not be prepared.")
        }
        worker.start()
        try {
            ready.receive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            error("Authorization could not be prepared.")
        } finally {
            ready.cancel()
        }
    }

    suspend fun await(id: Long) {
        when (attempts.await(id)) {
            OAuthAttemptOutcome.Committed -> Unit
            OAuthAttemptOutcome.Failed -> error("Authorization failed.")
            OAuthAttemptOutcome.TimedOut -> error("Authorization timed out.")
            OAuthAttemptOutcome.Cancelled -> error("Authorization was cancelled.")
            OAuthAttemptOutcome.Released -> error("Authorization was released.")
            OAuthAttemptOutcome.BackendStopped -> error("Authorization backend stopped.")
        }
    }

    suspend fun cancel(id: Long) {
        attempts.cancel(id)?.worker?.cancel()
    }

    suspend fun abandonPending(id: Long) {
        attempts.releasePending(id)?.worker?.cancel()
    }

    suspend fun cancelTarget(target: OAuthTarget) {
        attempts.cancelTarget(target).forEach { it.worker?.cancel() }
    }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        owner.cancel()
        attempts.closeAndJoin()
        owner.coroutineContext.job.cancelAndJoin()
    }

    private suspend fun runAttempt(
        slot: BackendOAuthAttemptRegistry.Attempt,
        ready: Channel<OAuthAuthorization>,
        elapsedBeforeStart: Duration,
    ) {
        var listener: OAuthLoopbackListener? = null
        var listenerClose: Deferred<Unit>? = null
        var innerId: Long? = null
        var watchdog: Job? = null
        try {
            val remaining = callbackDeadline - elapsedBeforeStart
            if (!remaining.isPositive()) {
                attempts.finish(slot, OAuthAttemptOutcome.TimedOut)
                return
            }
            val attemptJob = currentCoroutineContext().job
            watchdog = owner.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(remaining)
                if (attempts.finish(slot, OAuthAttemptOutcome.TimedOut)) {
                    attemptJob.cancel(CancellationException("Authorization deadline elapsed."))
                }
            }
            val callback = run {
                val opened = openListener(owner, candidates(slot.target))
                listener = opened
                val prepared = begin(slot.target, opened.redirectUri) { innerId = it }
                check(innerId == prepared.id) { "OAuth preparation identity mismatch." }
                val authorization = prepared.authorization.await()
                check(authorization.attemptId == prepared.id) { "OAuth preparation identity mismatch." }
                val state = authorizationState(authorization.url, opened.redirectUri)
                check(state == waitingState(prepared.id)) { "OAuth state binding mismatch." }
                check(attempts.waiting(slot)) { "OAuth attempt is no longer pending." }
                opened.armStateWithAdmission(state, OAuthCallbackValuePolicy.RequireNonBlank) {
                    holdPendingDeadline(prepared.id) && attempts.accept(slot)
                }
                ready.trySend(OAuthAuthorization(slot.id, authorization.url)).getOrThrow()
                opened.awaitCallback()
            }
            watchdog.cancel()
            listener?.let { opened ->
                listenerClose = owner.async(start = CoroutineStart.UNDISPATCHED) { opened.closeAndJoin() }
                listener = null
            }
            complete(requireNotNull(innerId), callback)
            attempts.finish(slot, OAuthAttemptOutcome.Committed)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (owner.coroutineContext.job.isActive) attempts.finish(slot, OAuthAttemptOutcome.Failed)
            }
            throw cancelled
        } catch (_: Throwable) {
            attempts.finish(slot, OAuthAttemptOutcome.Failed)
        } finally {
            watchdog?.cancel()
            withContext(NonCancellable) {
                val explicit = attempts.explicitlyCancelled(slot)
                innerId?.let { id ->
                    try {
                        if (explicit) cancelInner(id) else releaseInner(id)
                    } catch (_: Throwable) { runCatching { reportCleanupFailure() } }
                }
                try {
                    listener?.closeAndJoin()
                    listenerClose?.await()
                } catch (_: Throwable) { runCatching { reportCleanupFailure() } }
            }
        }
    }

    private suspend fun <R> accepted(block: suspend () -> R): R {
        currentCoroutineContext().ensureActive()
        owner.coroutineContext.ensureActive()
        return owner.async { block() }.await()
    }
}

private fun authorizationState(raw: String, redirectUri: String): String {
    require(raw.length <= 16_384 && raw.encodeToByteArray().size <= 16_384) {
        "Invalid authorization binding."
    }
    val url = try { Url(raw) } catch (_: Throwable) { throw IllegalArgumentException("Invalid authorization binding.") }
    val state = url.parameters.getAll("state")?.singleOrNull()
    require(
        raw.startsWith("${url.protocol.name}://") && raw.none { it <= ' ' || it == '\u007f' } &&
            url.protocol.name in setOf("http", "https") && url.host.isNotBlank() &&
            url.user == null && url.password == null && '#' !in raw && !state.isNullOrBlank() &&
            state.length <= 1024 && state.encodeToByteArray().size <= 1024 &&
            url.parameters.getAll("redirect_uri") == listOf(redirectUri),
    ) { "Invalid authorization binding." }
    return state
}
