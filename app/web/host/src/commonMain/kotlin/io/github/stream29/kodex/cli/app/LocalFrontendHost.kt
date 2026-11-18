package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.LocalBrowserEndpoint
import io.github.stream29.kodex.backend.LocalBrowserGrant
import io.github.stream29.kodex.backend.LocalBrowserGrantProtocol
import io.github.stream29.kodex.backend.LocalFrontendBootstrap
import io.github.stream29.kodex.backend.LocalFrontendBootstrapMaxBytes
import io.github.stream29.kodex.backend.LocalFrontendBootstrapProtocol
import io.github.stream29.kodex.backend.LocalFrontendHostJson
import io.github.stream29.kodex.backend.LocalWebTicket
import io.github.stream29.kodex.rpc.control.BackendControlClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.Source
import kotlinx.io.readByteArray

internal fun frontendAssetPath(path: String): Boolean = path.isNotEmpty() &&
    path.split('/').all { it.matches(Regex("[A-Za-z0-9_@.-]+")) && it != "." && it != ".." }

public actual suspend fun <R> withLocalFrontendHost(
    endpoint: BackendEndpoint,
    directory: String,
    openResource: (String) -> Source?,
    newSecret: () -> String,
    block: suspend CoroutineScope.(privateLaunchUrl: String) -> R,
): R = coroutineScope {
    require(endpoint.businessProtocol == BackendBusinessProtocol) { "Backend business protocol is incompatible." }
    val id = newSecret()
    val ticket = newSecret()
    val credential = newSecret()
    require(listOf(id, ticket, credential).all { it.matches(Regex("[0-9a-f]{64}")) })
    require(setOf(id, ticket, credential, endpoint.password).size == 4)
    check(openResource("index.html")?.use { true } == true) { "Embedded website unavailable." }
    val bootstrap = MutableStateFlow<String?>(null)
    val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            post("/web/$id/bootstrap") {
                call.frontendHeaders()
                if (!call.acceptsFrontendRequest(originRequired = true) || !call.acceptsBootstrapJson()) {
                    call.respondText("Frontend bootstrap rejected.", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val request = call.readBootstrapTicket()
                if (request?.ticket != ticket) {
                    call.respondText("Frontend capability required.", status = HttpStatusCode.Unauthorized)
                    return@post
                }
                // Reusing this reply never re-registers or renews the grant.
                val grant = bootstrap.value
                if (grant == null) call.respondText("Frontend bootstrap unavailable.", status = HttpStatusCode.ServiceUnavailable)
                else call.respondText(grant, ContentType.Application.Json)
            }
            get("/web/$id/{path...}") {
                call.frontendHeaders()
                val path = call.parameters.getAll("path")?.joinToString("/").orEmpty().ifEmpty { "index.html" }
                if (!call.acceptsFrontendRequest(originRequired = false) || !frontendAssetPath(path)) {
                    call.respondText("Not found.", status = HttpStatusCode.NotFound)
                    return@get
                }
                val resource = openResource(path)
                if (resource == null) {
                    call.respondText("Not found.", status = HttpStatusCode.NotFound)
                    return@get
                }
                resource.use { call.respondSource(it, frontendAssetType(path)) }
            }
        }
    }
    val control = BackendControlClient()
    var grant: LocalBrowserGrant? = null
    var primary: Throwable? = null
    try {
        engine.startSuspend()
        val port = engine.engine.resolvedConnectors().single().port
        val origin = "http://127.0.0.1:$port"
        check(port != endpoint.port)
        val request = LocalBrowserGrant(LocalBrowserGrantProtocol, origin, credential, directory)
        // Record the exact grant before sending; a lost reply can still mean registration succeeded.
        grant = request
        val canonical = control.registerBrowser(endpoint, request)
        val info = LocalFrontendBootstrap(
            LocalFrontendBootstrapProtocol,
            LocalBrowserEndpoint(endpoint.instanceId, endpoint.port, credential, endpoint.businessProtocol),
            canonical,
        )
        val response = LocalFrontendHostJson.encodeToString(LocalFrontendBootstrap.serializer(), info)
        check(response.encodeToByteArray().size <= LocalFrontendBootstrapMaxBytes) { "Frontend bootstrap response is too large." }
        bootstrap.value = response
        block("$origin/web/$id/#$ticket")
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        withContext(NonCancellable) {
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() }
                catch (failure: Throwable) {
                    if (primary == null) primary = failure else if (primary !== failure) primary.addSuppressed(failure)
                }
            }
            bootstrap.value = null
            cleanup { engine.stopSuspend() }
            grant?.let {
                try { withTimeout(3_000) { control.revokeBrowser(endpoint, it) } }
                catch (_: Exception) { /* The finite grant also expires if the exact backend is gone. */ }
            }
            cleanup { control.close() }
        }
        if (primary != null && currentCoroutineContext().isActive) throw requireNotNull(primary)
    }
}

private fun ApplicationCall.acceptsFrontendRequest(originRequired: Boolean): Boolean {
    val host = "127.0.0.1:${request.local.localPort}"
    if (request.headers.getAll(HttpHeaders.Host)?.singleOrNull() != host) return false
    val origins = request.headers.getAll(HttpHeaders.Origin)
    if (origins == null && originRequired || origins != null && origins.singleOrNull() != "http://$host") return false
    return request.headers["Sec-Fetch-Site"] != "cross-site"
}

private fun ApplicationCall.frontendHeaders() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Frame-Options", "DENY")
    response.header("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
}

private fun ApplicationCall.acceptsBootstrapJson(): Boolean {
    val value = request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull() ?: return false
    return try { parseHeaderValue(value).size == 1 && ContentType.parse(value).match(ContentType.Application.Json) }
    catch (_: BadContentTypeFormatException) { false }
}

private suspend fun ApplicationCall.readBootstrapTicket(): LocalWebTicket? {
    return try {
        val bytes = receiveChannel().readRemaining(1_025).use { it.readByteArray() }
        if (bytes.size > 1_024) return null
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        LocalFrontendHostJson.decodeFromString(LocalWebTicket.serializer(), text)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}

private fun frontendAssetType(path: String): ContentType = when (path.substringAfterLast('.')) {
    "html" -> ContentType.Text.Html
    "js", "mjs" -> ContentType.Application.JavaScript
    "wasm" -> ContentType("application", "wasm")
    "css" -> ContentType.Text.CSS
    "json" -> ContentType.Application.Json
    "ttf" -> ContentType("font", "ttf")
    "woff" -> ContentType("font", "woff")
    "woff2" -> ContentType("font", "woff2")
    "txt", "md" -> ContentType.Text.Plain
    else -> ContentType.Application.OctetStream
}
