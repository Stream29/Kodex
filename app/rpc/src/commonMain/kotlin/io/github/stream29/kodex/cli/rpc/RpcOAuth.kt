package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public actual suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
    callbackProvider: RpcOAuthCallbackProvider,
    mcpRedirectUri: String?,
): KodexAuthLoginAttempt {
    val listener = callbackProvider.listen(scope, target, mcpRedirectUri)
    var id: Long? = null
    try {
        val prepared = rpc.startOAuthLogin(target, listener.redirectUri)
        id = prepared.attemptId
        val state = Url(prepared.url).parameters.getAll("state")?.singleOrNull()
        require(!state.isNullOrBlank()) { "The authorization response has no unique state." }
        listener.expectState(state)
        val owner = SupervisorJob(scope.coroutineContext[Job])
        val local = CoroutineScope(scope.coroutineContext + owner)
        val work = local.async(start = CoroutineStart.UNDISPATCHED) {
            var completed = false
            try {
                val callback = withTimeoutOrNull(10.minutes) { listener.awaitCallback() }
                    ?: error("Sign-in callback timed out.")
                // Only callback waiting expires. Accepted exchange/persistence has its own
                // backend lifetime, independent of this local waiter's cancellation.
                rpc.completeOAuthLogin(prepared.attemptId, callback)
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Provider/network messages may contain transient authorization data.
                error("Sign-in failed. Check the current authentication state before trying again.")
            } finally {
                withContext(NonCancellable) {
                    try { listener.close() }
                    finally { if (!completed) cancelExact(rpc, prepared.attemptId) }
                }
            }
        }
        work.invokeOnCompletion { owner.complete() }
        return object : KodexAuthLoginAttempt {
            override val authorizationUrl: String = prepared.url
            override suspend fun awaitCompletion() { work.await() }
            override fun cancel() { work.cancel() }
        }
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            try { listener.close() }
            finally { id?.let { cancelExact(rpc, it) } }
        }
        if (error is CancellationException) throw error
        error("Unable to prepare sign-in.")
    }
}

private suspend fun cancelExact(rpc: GlobalRpc, id: Long) {
    // Best-effort bounded cleanup. A lost start reply has no id; backend expiry covers it.
    try { withTimeoutOrNull(5.seconds) { rpc.cancelOAuthLogin(id) } }
    catch (_: Exception) { /* Never retry a different attempt or log credential-bearing errors. */ }
}
