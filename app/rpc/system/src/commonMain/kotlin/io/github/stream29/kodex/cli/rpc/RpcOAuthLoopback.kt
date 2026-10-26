package io.github.stream29.kodex.cli.rpc

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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

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
        // CIO can report a bind failure from an engine child as well as startSuspend.
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
