package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Browser URLs and callback payloads live only for the attempt; neither is logged. */
public suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
    mcpRedirectUri: String? = null,
): KodexAuthLoginAttempt = startRpcOAuth(rpc, target, scope, mcpRedirectUri) { owner, candidates ->
    startLoopback(owner, candidates)
}

internal interface OAuthCallbackListener {
    val redirectUri: String
    fun expectState(value: String)
    suspend fun awaitCallback(): String
    suspend fun close()
}

internal suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
    mcpRedirectUri: String?,
    listen: suspend (CoroutineScope, List<String>) -> OAuthCallbackListener,
): KodexAuthLoginAttempt {
    val redirects = when (target) {
        is OAuthTarget.OpenAi -> listOf("http://localhost:1455/auth/callback", "http://localhost:1457/auth/callback")
        is OAuthTarget.Mcp -> listOf(requireNotNull(mcpRedirectUri))
    }
    val listener = listen(scope, redirects)
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

internal suspend fun startLoopback(
    scope: CoroutineScope,
    candidates: List<String>,
    testPort: Int? = null,
): OAuthCallbackListener {
    for (redirect in candidates) {
        val url = Url(redirect)
        require(url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1"))
        require(url.parameters.isEmpty() && url.fragment.isEmpty() && url.user == null && url.password == null)
        val owner = SupervisorJob(scope.coroutineContext[Job])
        val expectedState = MutableStateFlow<String?>(null)
        val callback = CompletableDeferred<String>()
        var actualRedirect = redirect
        // CIO may report bind failure from an engine child in addition to startSuspend.
        // Route it to this listener instead of an unhandled Native coroutine exception.
        val errors = CoroutineExceptionHandler { _, failure -> callback.completeExceptionally(failure) }
        val server: EmbeddedServer<*, *> = CoroutineScope(scope.coroutineContext + owner + errors).embeddedServer(
            CIO, port = testPort ?: url.port, host = "127.0.0.1",
        ) {
            routing {
                get(url.encodedPath) {
                    val query = call.request.queryParameters
                    val expected = expectedState.value
                    val valid = expected != null && query.getAll("state") == listOf(expected) &&
                        listOf("code", "error").all { query.getAll(it).orEmpty().size <= 1 } &&
                        ((query["code"] != null) xor (query["error"] != null))
                    if (valid) {
                        val rawQuery = call.request.uri.substringAfter('?', "")
                        callback.complete("$actualRedirect?$rawQuery")
                    }
                    call.respondText(
                        if (valid) "Response received. Return to Kodex for the result." else "Invalid sign-in response.",
                        status = if (valid) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                    )
                }
            }
        }
        try {
            server.startSuspend()
            val port = server.engine.resolvedConnectors().single().port
            actualRedirect = URLBuilder(redirect).apply { this.port = port }.buildString()
            return object : OAuthCallbackListener {
                override val redirectUri: String = actualRedirect
                override fun expectState(value: String) { expectedState.value = value }
                override suspend fun awaitCallback(): String = callback.await()
                override suspend fun close() {
                    callback.cancel()
                    try { server.stopSuspend() } finally { owner.cancel(); owner.join() }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try { server.stopSuspend() } finally { owner.cancel(); owner.join() }
            }
            if (error is CancellationException && !scope.coroutineContext.isActive) throw error
        }
    }
    error("Unable to bind a supported local callback listener.")
}
