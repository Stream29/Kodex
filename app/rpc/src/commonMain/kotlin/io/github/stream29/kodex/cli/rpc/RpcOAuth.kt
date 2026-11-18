package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthLoginEvent
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

/** [rpc] must use one physical connection for both the stream and exact cancellation. */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
): KodexAuthLoginAttempt {
    currentCoroutineContext().ensureActive()
    val ready = CompletableDeferred<OAuthAuthorization>()
    val completion = CompletableDeferred<Unit>()
    val work = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            currentCoroutineContext().ensureActive()
            var committed = false
            rpc.startOAuthLogin(target).collect { event ->
                when (event) {
                    is OAuthLoginEvent.Authorization -> check(ready.complete(event.authorization)) {
                        "Duplicate authorization event."
                    }
                    OAuthLoginEvent.Committed -> {
                        check(ready.isCompleted && !committed) { "Unexpected credential commit event." }
                        committed = true
                    }
                }
            }
            check(committed) { "The login operation ended before credentials were committed." }
            completion.complete(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ready.completeExceptionally(IllegalStateException("Unable to prepare sign-in."))
            completion.completeExceptionally(
                IllegalStateException("Sign-in failed. Check the current authentication state before trying again."),
            )
        }
    }
    work.invokeOnCompletion { failure ->
        val cancelled = failure as? CancellationException ?: CancellationException("Login observation ended.")
        ready.cancel(cancelled)
        completion.cancel(cancelled)
    }
    val prepared = try {
        ready.await()
    } catch (failure: Throwable) {
        work.cancel()
        throw failure
    }
    val cancelSent = AtomicBoolean(false)
    return object : KodexAuthLoginAttempt {
        override val authorizationUrl: String = prepared.url
        override suspend fun awaitCompletion() {
            try {
                completion.await()
            } catch (cancelled: CancellationException) {
                work.cancel()
                throw cancelled
            }
        }
        override fun release() { work.cancel() }
        override fun cancel() {
            if (!cancelSent.compareAndSet(false, true)) return
            CoroutineScope(scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    cancelExact(rpc, prepared.attemptId)
                } finally {
                    work.cancel()
                }
            }
        }
    }
}

private suspend fun cancelExact(rpc: GlobalRpc, id: Long) {
    try { withTimeoutOrNull(5.seconds) { rpc.cancelOAuthLogin(id) } }
    catch (_: Exception) { /* Do not retry an unknown outcome or log authorization data. */ }
}
