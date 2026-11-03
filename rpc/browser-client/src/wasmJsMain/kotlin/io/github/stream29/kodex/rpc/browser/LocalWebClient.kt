@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

internal fun validateLocalWebLocation(origin: String, path: String, ticket: String?) {
    require(origin == canonicalLocalBrowserOrigin(origin) && origin.startsWith("http://127.0.0.1:")) {
        "Invalid local Web origin."
    }
    require(path.matches(Regex("/web/[0-9a-f]{64}/"))) { "Invalid local Web context." }
    require(ticket == null || ticket.matches(Regex("[0-9a-f]{64}"))) { "Invalid frontend capability." }
}

public actual suspend fun <R> withLocalWebClient(
    origin: String,
    path: String,
    ticket: String?,
    block: suspend CoroutineScope.(canonicalDirectory: String, FrontendRpcConnection) -> R,
): R {
    validateLocalWebLocation(origin, path, ticket)
    require(origin == pageOrigin()) { "Local Web context must use this page's origin." }
    return withLocalFrontendClient(origin, path, ticket) { directory, connection, _ ->
        block(directory, connection)
    }
}

internal suspend fun bootstrapLocalFrontend(origin: String, path: String, ticket: String?): LocalFrontendBootstrap {
    require(ticket != null) { "Frontend capability unavailable. Run kodex web or kodex app again." }
    val http = HttpClient(Js) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    return try {
        val info = http.preparePost(origin + path + "bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(LocalWebTicket.serializer(), LocalWebTicket(ticket)))
        }.execute { reply ->
            check(reply.status.value in 200..299) { "Frontend bootstrap rejected." }
            // Read inside the streaming statement so Ktor never pre-buffers an unbounded reply.
            decodeBootstrap(readFrontendFrame(reply))
        }
        info.also {
            validateFrontendBootstrap(it, origin, origin)
            validateBrowserEndpoint(it.endpoint)
        }
    } catch (failure: Throwable) {
        currentCoroutineContext().ensureActive()
        if (!isBrowserNetworkFailure(failure)) throw failure
        throw IllegalStateException("Frontend bootstrap unavailable. Run kodex web or kodex app again.")
    } finally { http.close() }
}

@JsFun("() => location.origin")
private external fun pageOrigin(): String
