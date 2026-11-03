package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

public actual suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
): KodexAuthLoginAttempt {
    var id: Long? = null
    try {
        val prepared = rpc.startOAuthLogin(target)
        id = prepared.attemptId
        val owner = SupervisorJob(scope.coroutineContext[Job])
        var started = false
        val work = CoroutineScope(scope.coroutineContext + owner).async(start = CoroutineStart.UNDISPATCHED) {
            started = true
            try {
                currentCoroutineContext().ensureActive()
                rpc.awaitOAuthLogin(prepared.attemptId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                error("Sign-in failed. Check the current authentication state before trying again.")
            } finally {
                withContext(NonCancellable) { abandonPending(rpc, prepared.attemptId) }
            }
        }
        work.invokeOnCompletion {
            owner.complete()
            if (!started) {
                CoroutineScope(scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.UNDISPATCHED) {
                    abandonPending(rpc, prepared.attemptId)
                }
            }
        }
        return object : KodexAuthLoginAttempt {
            override val authorizationUrl: String = prepared.url
            override suspend fun awaitCompletion() {
                try { work.await() }
                catch (cancelled: CancellationException) {
                    work.cancel()
                    throw cancelled
                }
            }
            override fun release() { work.cancel() }
            override fun cancel() {
                CoroutineScope(scope.coroutineContext + NonCancellable).launch(start = CoroutineStart.UNDISPATCHED) {
                    cancelExact(rpc, prepared.attemptId)
                }
                work.cancel()
            }
        }
    } catch (failure: Throwable) {
        withContext(NonCancellable) { id?.let { abandonPending(rpc, it) } }
        if (failure is CancellationException) throw failure
        error("Unable to prepare sign-in.")
    }
}

private suspend fun cancelExact(rpc: GlobalRpc, id: Long) {
    try { withTimeoutOrNull(5.seconds) { rpc.cancelOAuthLogin(id) } }
    catch (_: Exception) { /* Do not retry an unknown outcome or log authorization data. */ }
}

private suspend fun abandonPending(rpc: GlobalRpc, id: Long) {
    try { withTimeoutOrNull(5.seconds) { rpc.abandonPendingOAuthLogin(id) } }
    catch (_: Exception) { /* The backend also expires an undelivered pending attempt. */ }
}
