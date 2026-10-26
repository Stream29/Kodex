package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.control.backendRegisterBrowser
import io.github.stream29.kodex.rpc.control.backendRevokeBrowser
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
import kotlinx.serialization.json.Json

internal fun frontendAssetPath(path: String): Boolean = path.isNotEmpty() &&
    path.split('/').all { it.matches(Regex("[A-Za-z0-9_@.-]+")) && it != "." && it != ".." }

/** Owns only static resources and bootstrap. Business sockets/renewal go directly from Wasm to backend. */
public actual suspend fun <R> withLocalFrontendHost(
    endpoint: BackendEndpoint,
    directory: String,
    openResource: (String) -> Source?,
    newSecret: () -> String,
    block: suspend CoroutineScope.(privateLaunchUrl: String) -> R,
): R = coroutineScope {
    val id = newSecret()
    val capability = newSecret()
    val credential = newSecret()
    require(listOf(id, capability, credential).all { it.matches(Regex("[0-9a-f]{64}")) })
    require(setOf(id, capability, credential, endpoint.password).size == 4)
    check(openResource("index.html")?.use { true } == true) { "Embedded website unavailable." }
    val bootstrap = MutableStateFlow<LocalFrontendBootstrap?>(null)
    val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        fun ApplicationCall.origin() = "http://127.0.0.1:${request.local.localPort}"
        fun ApplicationCall.acceptsOrigin(required: Boolean): Boolean {
            if (request.headers.getAll(HttpHeaders.Host)?.singleOrNull() !=
                "127.0.0.1:${request.local.localPort}") return false
            val origins = request.headers.getAll(HttpHeaders.Origin)
            if (origins == null && required || origins != null && origins.singleOrNull() != origin()) return false
            if (request.headers["Sec-Fetch-Site"] == "cross-site") return false
            return true
        }
        fun ApplicationCall.headers() {
            response.header(HttpHeaders.CacheControl, "no-store")
            response.header("X-Content-Type-Options", "nosniff")
            response.header("Referrer-Policy", "no-referrer")
            response.header("X-Frame-Options", "DENY")
            response.header("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        }
        routing {
            post("/web/$id/bootstrap") {
                call.headers()
                val contentType = call.request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull()
                val acceptsJson = contentType?.let {
                    try { parseHeaderValue(it).size == 1 && ContentType.parse(it).match(ContentType.Application.Json) }
                    catch (_: BadContentTypeFormatException) { false }
                } == true
                if (!call.acceptsOrigin(required = true) || !acceptsJson) {
                    call.respondText("Frontend bootstrap rejected.", status = HttpStatusCode.Forbidden)
                    return@post
                }
                val bytes = call.receiveChannel().readRemaining(1_025).use { it.readByteArray() }
                val ticket = if (bytes.size > 1_024) null else try {
                    Json.decodeFromString(LocalWebTicket.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (ticket?.ticket != capability) {
                    call.respondText("Frontend capability required.", status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val info = bootstrap.value
                if (info == null) {
                    call.respondText("Frontend bootstrap unavailable.", status = HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                call.respondText(Json.encodeToString(LocalFrontendBootstrap.serializer(), info), ContentType.Application.Json)
            }
            get("/web/$id/{path...}") {
                call.headers()
                val path = call.parameters.getAll("path")?.joinToString("/").orEmpty().ifEmpty { "index.html" }
                if (!call.acceptsOrigin(required = false) || !frontendAssetPath(path)) {
                    call.respondText("Not found.", status = HttpStatusCode.NotFound)
                    return@get
                }
                val resource = openResource(path)
                if (resource == null) {
                    call.respondText("Not found.", status = HttpStatusCode.NotFound)
                    return@get
                }
                val type = when (path.substringAfterLast('.')) {
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
                resource.use { call.respondSource(it, type) }
            }
        }
    }
    var grant: LocalBrowserGrant? = null
    var primary: Throwable? = null
    try {
        engine.startSuspend()
        val port = engine.engine.resolvedConnectors().single().port
        val origin = "http://127.0.0.1:$port"
        check(port != endpoint.port)
        val request = LocalBrowserGrant(LocalBrowserGrantProtocol, origin, credential, directory)
        grant = request
        val canonicalDirectory = backendRegisterBrowser(endpoint, request)
        bootstrap.value = LocalFrontendBootstrap(LocalBrowserGrantProtocol,
            LocalBrowserEndpoint(endpoint.instanceId, endpoint.port, credential, endpoint.businessProtocol),
            canonicalDirectory)
        block("$origin/web/$id/#$capability")
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        withContext(NonCancellable) {
            bootstrap.value = null
            try { engine.stopSuspend() }
            catch (cleanup: Throwable) { if (primary == null) primary = cleanup else primary.addSuppressed(cleanup) }
            // Best effort for a lost registration reply too. The lease expires without browser renewal.
            grant?.let {
                try { withTimeout(3_000) { backendRevokeBrowser(endpoint, it) } }
                catch (_: Exception) { /* Backend may already have ended; never discover a replacement. */ }
            }
        }
        if (primary != null && currentCoroutineContext().isActive) throw requireNotNull(primary)
    }
}
