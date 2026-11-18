@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.LocalFrontendBootstrap
import io.github.stream29.kodex.backend.LocalFrontendBootstrapMaxBytes
import io.github.stream29.kodex.backend.LocalFrontendHostJson
import io.github.stream29.kodex.backend.LocalWebTicket
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray

internal suspend fun bootstrapLocalFrontend(origin: String, path: String, ticket: String?): LocalFrontendBootstrap {
    validateLocalWebLocation(origin, path, ticket)
    require(ticket != null) { "Frontend capability unavailable. Run kodex web or kodex app again." }
    val http = HttpClient(Js) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    return try {
        http.preparePost(origin + path + "bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(LocalFrontendHostJson.encodeToString(LocalWebTicket.serializer(), LocalWebTicket(ticket)))
        }.execute { reply ->
            check(reply.status.value in 200..299) { "Frontend bootstrap rejected." }
            decodeBootstrap(readFrontendFrame(reply))
        }
    } catch (failure: Throwable) {
        currentCoroutineContext().ensureActive()
        if (!isBrowserNetworkFailure(failure)) throw failure
        throw IllegalStateException("Frontend bootstrap unavailable. Run kodex web or kodex app again.")
    } finally {
        http.close()
    }
}

public actual suspend fun <R> withLocalFrontendClient(
    origin: String,
    path: String,
    ticket: String?,
    block: suspend CoroutineScope.(String, FrontendRpcConnection) -> R,
): R {
    require(origin == frontendPageOrigin()) { "Local frontend must use this page's origin." }
    val info = bootstrapLocalFrontend(origin, path, ticket)
    return withLocalBrowserClient(info.endpoint, info.workingDirectory) { canonical, connection ->
        check(canonical == info.workingDirectory) { "Backend working directory changed." }
        block(canonical, connection)
    }
}

internal fun decodeBootstrap(text: String): LocalFrontendBootstrap {
    check(text.encodeToByteArray().size <= LocalFrontendBootstrapMaxBytes) { "Frontend bootstrap is too large." }
    val info = try { LocalFrontendHostJson.decodeFromString(LocalFrontendBootstrap.serializer(), text) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { error("Invalid frontend bootstrap response.") }
    validateFrontendBootstrap(info)
    return info
}

internal suspend fun readFrontendFrame(response: HttpResponse): String {
    val channel = response.bodyAsChannel()
    val bytes = try {
        channel.readRemaining(LocalFrontendBootstrapMaxBytes.toLong() + 1).use { it.readByteArray() }
    } finally {
        channel.cancel(null)
    }
    check(bytes.size <= LocalFrontendBootstrapMaxBytes) { "Frontend response is too large." }
    return try { bytes.decodeToString(throwOnInvalidSequence = true) }
    catch (_: Exception) { error("Invalid frontend response encoding.") }
}

@JsFun("() => location.origin")
private external fun frontendPageOrigin(): String
